package io.github.joeyblanks.ecommerce.cart.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.cart.config.RabbitConfig;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository;
import io.github.joeyblanks.ecommerce.cart.support.CatalogStub;
import io.github.joeyblanks.ecommerce.cart.support.TestJwks;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * cart-service consuming real OrderPlaced messages from a real RabbitMQ, on a real Redis, plus the
 * service-token separation on every cart endpoint. Run by Failsafe; requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CartOrderPlacedIT {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static final TestJwks JWKS = new TestJwks();
    static final CatalogStub CATALOG = new CatalogStub();
    static final String EXCHANGE = "ecommerce.events";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("app.security.jwk-set-uri", JWKS::url);
        r.add("app.security.issuer", () -> TestJwks.ISSUER);
        r.add("app.catalog.base-url", CATALOG::url);
        r.add("app.cart.cas-max-attempts", () -> "25");
        r.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
        r.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
    }

    @AfterAll
    static void stop() {
        JWKS.close();
        CATALOG.close();
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;

    @BeforeEach
    void clean() {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        CATALOG.reset();
        admin.initialize();
        admin.purgeQueue(RabbitConfig.ORDER_PLACED_DLQ, false);
    }

    // ---------- helpers ----------

    private static String bearer(UUID user) {
        return "Bearer " + JWKS.token(user.toString(), "CUSTOMER");
    }

    private ResultActions add(UUID user, UUID product, int quantity) throws Exception {
        return mvc.perform(post("/api/cart/items").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + product + "\",\"quantity\":" + quantity + "}"));
    }

    private int quantityInCart(UUID user, UUID product) {
        Object raw = redis.opsForHash().get(CartRepository.key(user), product.toString());
        if (raw == null) {
            return 0;
        }
        String s = (String) raw;
        int i = s.indexOf("\"quantity\":") + "\"quantity\":".length();
        int j = i;
        while (Character.isDigit(s.charAt(j))) {
            j++;
        }
        return Integer.parseInt(s.substring(i, j));
    }

    /** Publishes an OrderPlaced exactly as order-service does (docs/events). */
    private void publishOrderPlaced(UUID eventId, UUID user, Object... productAndQuantity) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":\"").append(productAndQuantity[i]).append("\",\"sku\":\"S\",\"name\":\"N\",")
                    .append("\"unitPrice\":\"10.00\",\"quantity\":").append(productAndQuantity[i + 1]).append(",\"lineTotal\":\"10.00\"}");
        }
        String body = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderPlaced\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T12:00:00Z\",\"producer\":\"order-service\",\"data\":{\"orderId\":\""
                + UUID.randomUUID() + "\",\"userId\":\"" + user + "\",\"items\":[" + items + "],\"totalAmount\":\"10.00\","
                + "\"placedAt\":\"2026-11-14T12:00:00Z\"}}";
        send(eventId.toString(), body);
    }

    private void send(String messageId, String body) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setType("OrderPlaced");
        p.setContentType("application/json");
        rabbit.send(EXCHANGE, "order.placed", new Message(body.getBytes(StandardCharsets.UTF_8), p));
    }

    private static void eventually(Runnable assertion) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(assertion::run);
    }

    // ---------- removing what was ordered ----------

    @Test
    void orderedItemsLeaveTheCart_partlyOrderedLinesAreReduced_othersStay() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        var pen = CATALOG.put("PEN", "Pen", "1.00", 100);
        var mug = CATALOG.put("MUG", "Mug", "5.00", 100);
        add(user, book.id(), 2).andExpect(status().isOk());
        add(user, pen.id(), 5).andExpect(status().isOk());
        add(user, mug.id(), 1).andExpect(status().isOk());

        publishOrderPlaced(UUID.randomUUID(), user, book.id(), 2, pen.id(), 3); // the mug was not ordered

        eventually(() -> assertThat(quantityInCart(user, book.id())).isZero());
        assertThat(quantityInCart(user, pen.id())).isEqualTo(2);
        assertThat(quantityInCart(user, mug.id())).isEqualTo(1);
        mvc.perform(get("/api/cart").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.itemCount").value(2)).andExpect(jsonPath("$.totalQuantity").value(3));
    }

    @Test
    void whenEverythingIsOrderedTheCartIsGone() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        add(user, book.id(), 2).andExpect(status().isOk());

        publishOrderPlaced(UUID.randomUUID(), user, book.id(), 2);

        eventually(() -> assertThat(redis.hasKey(CartRepository.key(user))).isFalse());
        mvc.perform(get("/api/cart").header("Authorization", bearer(user))).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void itemsAddedAfterPlacingTheOrderAreKept() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        var pen = CATALOG.put("PEN", "Pen", "1.00", 100);
        add(user, book.id(), 2).andExpect(status().isOk());
        // order placed for 2 books; before the event arrives the customer adds 1 more book and a pen
        add(user, book.id(), 1).andExpect(status().isOk());
        add(user, pen.id(), 4).andExpect(status().isOk());

        publishOrderPlaced(UUID.randomUUID(), user, book.id(), 2);

        eventually(() -> assertThat(quantityInCart(user, book.id())).isEqualTo(1));
        assertThat(quantityInCart(user, pen.id())).isEqualTo(4);
    }

    @Test
    void aLineTheCustomerReducedMeanwhileIsRemovedNotMadeNegative() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        add(user, book.id(), 1).andExpect(status().isOk());

        publishOrderPlaced(UUID.randomUUID(), user, book.id(), 3);

        eventually(() -> assertThat(redis.hasKey(CartRepository.key(user))).isFalse());
    }

    @Test
    void aRedeliveredEventIsAppliedOnlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        add(user, book.id(), 10).andExpect(status().isOk());
        UUID eventId = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            publishOrderPlaced(eventId, user, book.id(), 2);
        }

        eventually(() -> assertThat(quantityInCart(user, book.id())).isEqualTo(8));
        Thread.sleep(1500); // give the duplicates every chance to (wrongly) apply
        assertThat(quantityInCart(user, book.id())).as("10 - 2, not 10 - 6").isEqualTo(8);
        assertThat(redis.hasKey(CartRepository.processedKey(eventId))).isTrue();
        assertThat(redis.getExpire(CartRepository.processedKey(eventId))).as("remembered ~30 days").isGreaterThan(29L * 24 * 3600);
    }

    @Test
    void anOrderForAUserWithoutACartIsANoOpAndIsNotDeadLettered() {
        UUID eventId = UUID.randomUUID();
        publishOrderPlaced(eventId, UUID.randomUUID(), UUID.randomUUID(), 1);

        eventually(() -> assertThat(redis.hasKey(CartRepository.processedKey(eventId))).isTrue());
        assertThat(rabbit.receive(RabbitConfig.ORDER_PLACED_DLQ, 1000)).isNull();
    }

    @Test
    void aMalformedEventIsDeadLettered() {
        send("bad-1", "{ not an OrderPlaced");

        Message dead = rabbit.receive(RabbitConfig.ORDER_PLACED_DLQ, 10_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains("not an OrderPlaced");
    }

    @Test
    void anOrderDoesNotCountAsCustomerActivity_theInactivityTimerIsNotRenewed() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        add(user, book.id(), 5).andExpect(status().isOk());
        redis.expire(CartRepository.key(user), Duration.ofSeconds(600)); // pretend the cart has been idle

        publishOrderPlaced(UUID.randomUUID(), user, book.id(), 2);

        eventually(() -> assertThat(quantityInCart(user, book.id())).isEqualTo(3));
        assertThat(redis.getExpire(CartRepository.key(user))).isBetween(1L, 600L);
    }

    @Test
    void customerChangesAtTheSameTimeAsTheOrderAreNeverLost() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 1000);
        add(user, book.id(), 10).andExpect(status().isOk());
        int customerAdds = 15;

        // the order (removing 4) arrives while the customer is adding 15 more, one at a time
        runConcurrently(customerAdds + 1, i -> {
            if (i == 0) {
                publishOrderPlaced(UUID.randomUUID(), user, book.id(), 4);
            } else {
                add(user, book.id(), 1).andExpect(status().isOk());
            }
        });

        eventually(() -> assertThat(quantityInCart(user, book.id())).isEqualTo(10 + customerAdds - 4));
    }

    // ---------- token separation ----------

    @Test
    void serviceTokensAreRefusedOnEveryCartEndpoint() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("BOOK", "Book", "10.00", 100);
        add(user, book.id(), 1).andExpect(status().isOk());
        String service = "Bearer " + JWKS.serviceToken("order-service", "product-catalog-service", List.of("stock:reserve"));
        String json = "{\"productId\":\"" + book.id() + "\",\"quantity\":1}";

        MockHttpServletRequestBuilder[] endpoints = {
                get("/api/cart"),
                post("/api/cart/items").contentType(MediaType.APPLICATION_JSON).content(json),
                put("/api/cart/items/" + book.id()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"),
                delete("/api/cart/items/" + book.id()),
                delete("/api/cart")};
        for (MockHttpServletRequestBuilder e : endpoints) {
            assertThat(mvc.perform(e.header("Authorization", service)).andReturn().getResponse().getStatus())
                    .as("service token").isEqualTo(403);
        }
        assertThat(quantityInCart(user, book.id())).as("nothing changed").isEqualTo(1);
        assertThat(CATALOG.hits()).as("refused before any work").isEqualTo(1);
        mvc.perform(get("/api/cart").header("Authorization", bearer(user))).andExpect(status().isOk());
    }

    // ---------- helper ----------

    private interface Task {
        void run(int index) throws Exception;
    }

    private static void runConcurrently(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                task.run(index);
                return null;
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }
}
