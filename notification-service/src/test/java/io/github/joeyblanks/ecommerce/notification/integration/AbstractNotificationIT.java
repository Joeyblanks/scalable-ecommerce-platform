package io.github.joeyblanks.ecommerce.notification.integration;

import io.github.joeyblanks.ecommerce.notification.config.RabbitConfig;
import io.github.joeyblanks.ecommerce.notification.support.ControlledEmailSender;
import io.github.joeyblanks.ecommerce.notification.support.FakeUserService;
import io.github.joeyblanks.ecommerce.notification.support.MailpitClient;
import io.github.joeyblanks.ecommerce.notification.support.TestMailConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL, RabbitMQ and Mailpit (a real SMTP server with an HTTP API), started once for all integration
 * test classes. user-service is played by {@link FakeUserService} over real HTTP. The real SMTP sender is wrapped
 * in {@link ControlledEmailSender} only to count calls and inject failures.
 */
@SpringBootTest
@Import(TestMailConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "app.jobs.worker-interval=100ms")
abstract class AbstractNotificationIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));
    static final FakeUserService USERS = new FakeUserService();
    static final String EXCHANGE = "ecommerce.events";

    static {
        POSTGRES.start();
        RABBIT.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
        r.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
        r.add("spring.mail.host", MAILPIT::getHost);
        r.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        r.add("app.user-service.base-url", USERS::baseUrl);
        r.add("app.user-service.token-uri", () -> USERS.baseUrl() + "/oauth2/token");
        r.add("app.user-service.client-secret", () -> FakeUserService.CLIENT_SECRET);
        r.add("app.notifications.retry-initial-delay", () -> "100ms");
        r.add("app.notifications.retry-max-delay", () -> "400ms");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired ControlledEmailSender sender;
    final MailpitClient mailbox = new MailpitClient(MAILPIT.getHost(), MAILPIT.getMappedPort(8025));

    @BeforeEach
    void clean() {
        sender.reset();
        USERS.reset();
        mailbox.clear();
        jdbc.execute("TRUNCATE notifications");
        admin.initialize();
        admin.purgeQueue(RabbitConfig.ORDER_EVENTS_QUEUE, false);
        admin.purgeQueue(RabbitConfig.ORDER_EVENTS_DLQ, false);
    }

    // ------------------------------------------------------------------ events in

    static String envelope(UUID eventId, String type, String data) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T10:00:00Z\",\"producer\":\"order-service\",\"data\":" + data + "}";
    }

    static String placedJson(UUID eventId, UUID orderId, UUID userId, String itemName, String total) {
        return envelope(eventId, "OrderPlaced", "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\",\"items\":[{\"productId\":\""
                + UUID.randomUUID() + "\",\"sku\":\"B\",\"name\":\"" + itemName + "\",\"unitPrice\":\"" + total
                + "\",\"quantity\":1,\"lineTotal\":\"" + total + "\"}],\"totalAmount\":\"" + total + "\",\"placedAt\":\"2026-11-14T10:00:00Z\"}");
    }

    static String paidJson(UUID eventId, UUID orderId, UUID userId) {
        return envelope(eventId, "OrderPaid", "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\",\"paymentId\":\""
                + UUID.randomUUID() + "\",\"paidAt\":\"2026-11-14T10:01:00Z\"}");
    }

    static String cancelledJson(UUID eventId, UUID orderId, UUID userId, String reason) {
        return envelope(eventId, "OrderCancelled", "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\",\"reason\":\""
                + reason + "\",\"cancelledAt\":\"2026-11-14T10:02:00Z\"}");
    }

    void send(String routingKey, String body) {
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), p));
    }

    // ------------------------------------------------------------------ observing

    int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class);
    }

    int rows(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status = ?", Integer.class, status);
    }

    String statusOf(UUID eventId) {
        return jdbc.query("SELECT status FROM notifications WHERE event_id = ?", (rs, n) -> rs.getString(1), eventId)
                .stream().findFirst().orElse(null);
    }

    static void await(Callable<Boolean> condition) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).until(condition);
    }

    void awaitStatus(UUID eventId, String status) {
        await(() -> status.equals(statusOf(eventId)));
    }

    int dlqSize() {
        return (int) admin.getQueueInfo(RabbitConfig.ORDER_EVENTS_DLQ).getMessageCount();
    }
}
