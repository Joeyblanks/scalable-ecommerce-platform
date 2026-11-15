package io.github.joeyblanks.ecommerce.payment.integration;

import static org.awaitility.Awaitility.await;

import io.github.joeyblanks.ecommerce.payment.config.RabbitConfig;
import io.github.joeyblanks.ecommerce.payment.support.ControlledProvider;
import io.github.joeyblanks.ecommerce.payment.support.TestJwks;
import io.github.joeyblanks.ecommerce.payment.support.TestProviderConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL and RabbitMQ (started once for all integration test classes), real JWKS over HTTP, and
 * the real simulated provider wrapped in {@link ControlledProvider}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestProviderConfig.class)
abstract class AbstractPaymentIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");
    static final TestJwks JWKS = new TestJwks();
    static final String EXCHANGE = "ecommerce.events";
    static final String CAPTURE = "test.payment-events";

    static {
        POSTGRES.start();
        RABBIT.start();
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
        r.add("app.security.jwk-set-uri", JWKS::url);
        r.add("app.security.issuer", () -> TestJwks.ISSUER);
        r.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
        r.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
    }

    @AfterAll
    static void stopJwks() {
        // JWKS server is shared by all classes in this JVM; it is closed when the JVM exits.
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired ControlledProvider provider;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        provider.reset();
        jdbc.execute("TRUNCATE payments, outbox_events, processed_events, simulated_charges");
        admin.initialize(); // (re)declare the application's topology
        admin.purgeQueue(RabbitConfig.ORDER_EVENTS_QUEUE, false);
        admin.purgeQueue(RabbitConfig.ORDER_EVENTS_DLQ, false);
    }

    // ------------------------------------------------------------------ events in

    static String placedJson(UUID eventId, UUID orderId, UUID userId, String total) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderPlaced\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T10:00:00Z\",\"producer\":\"order-service\",\"data\":{\"orderId\":\"" + orderId
                + "\",\"userId\":\"" + userId + "\",\"items\":[{\"productId\":\"" + UUID.randomUUID()
                + "\",\"sku\":\"B\",\"name\":\"Book\",\"unitPrice\":\"" + total + "\",\"quantity\":1,\"lineTotal\":\"" + total
                + "\"}],\"totalAmount\":\"" + total + "\",\"placedAt\":\"2026-11-14T10:00:00Z\"}}";
    }

    static String cancelledJson(UUID eventId, UUID orderId, UUID userId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderCancelled\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T10:00:00Z\",\"producer\":\"order-service\",\"data\":{\"orderId\":\"" + orderId
                + "\",\"userId\":\"" + userId + "\",\"reason\":\"CUSTOMER_REQUEST\",\"cancelledAt\":\"2026-11-14T10:00:00Z\"}}";
    }

    void send(String routingKey, String body) {
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), p));
    }

    void sendPlaced(UUID orderId, UUID userId, String total) {
        send("order.placed", placedJson(UUID.randomUUID(), orderId, userId, total));
    }

    void sendCancelled(UUID orderId, UUID userId) {
        send("order.cancelled", cancelledJson(UUID.randomUUID(), orderId, userId));
    }

    // ------------------------------------------------------------------ observing

    String statusOf(UUID orderId) {
        return jdbc.query("SELECT status FROM payments WHERE order_id = ?", (rs, n) -> rs.getString(1), orderId)
                .stream().findFirst().orElse(null);
    }

    void awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> status.equals(statusOf(orderId)));
    }

    int outboxCount(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Integer.class, eventType);
    }

    int paymentRows() {
        return jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class);
    }

    /** How many times money was actually taken / given back at the (simulated) provider. */
    int ledgerCharges() {
        return jdbc.queryForObject("SELECT count(*) FROM simulated_charges WHERE outcome = 'SUCCEEDED'", Integer.class);
    }

    int ledgerRefunds() {
        return jdbc.queryForObject("SELECT count(*) FROM simulated_charges WHERE refund_ref IS NOT NULL", Integer.class);
    }

    static BigDecimal money(String s) {
        return new BigDecimal(s);
    }
}
