package io.github.joeyblanks.ecommerce.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.joeyblanks.ecommerce.order.config.RabbitConfig;
import io.github.joeyblanks.ecommerce.order.jobs.RetentionCleanupJob;
import io.github.joeyblanks.ecommerce.order.support.DependencyStub;
import io.github.joeyblanks.ecommerce.order.support.DependencyStub.CatalogMode;
import io.github.joeyblanks.ecommerce.order.support.TestJwks;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order saga end to end on a real PostgreSQL and a real RabbitMQ, with cart, catalog and token
 * endpoint stubbed over real HTTP. One test per failure case of the approved plan (ADR 007).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderSagaIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static final TestJwks JWKS = new TestJwks();
    static final DependencyStub STUB = new DependencyStub();
    static final String EXCHANGE = "ecommerce.events";
    static final String CAPTURE = "test.order-events";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("app.security.jwk-set-uri", JWKS::url);
        r.add("app.security.issuer", () -> TestJwks.ISSUER);
        r.add("app.service-token.token-uri", () -> STUB.url() + "/oauth2/token");
        r.add("app.service-token.client-secret", () -> DependencyStub.CLIENT_SECRET);
        r.add("app.cart.base-url", STUB::url);
        r.add("app.catalog.base-url", STUB::url);
        r.add("app.catalog.read-timeout", () -> "1s");
        r.add("app.jobs.outbox-relay-interval", () -> "100ms");
        r.add("app.jobs.stock-release-interval", () -> "100ms");
        r.add("app.jobs.reconcile-interval", () -> "200ms");
        r.add("app.jobs.payment-timeout-interval", () -> "200ms");
        r.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
        r.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
    }

    @AfterAll
    static void stopStubs() {
        JWKS.close();
        STUB.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RetentionCleanupJob cleanup;
    @Autowired io.github.joeyblanks.ecommerce.order.client.ServiceTokenClient tokens;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        STUB.reset();
        jdbc.execute("TRUNCATE order_items, orders, outbox_events, processed_events, stock_releases");
        admin.initialize(); // (re)declare the application's topology
        Queue capture = new Queue(CAPTURE, true, false, false) /* RabbitMQ 4 removed transient non-exclusive queues */;
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture).to(new TopicExchange(EXCHANGE)).with("order.#"));
        admin.purgeQueue(CAPTURE, false);
        admin.purgeQueue(RabbitConfig.PAYMENT_EVENTS_DLQ, false);
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private static String bearer(UUID user) {
        return "Bearer " + JWKS.userToken(user.toString(), "CUSTOMER");
    }

    private ResultActions place(UUID user, String key) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", bearer(user)).header("Idempotency-Key", key));
    }

    private UUID placeOk(UUID user) throws Exception {
        String body = place(user, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Next event published by order-service (captured from the real exchange). */
    private JsonNode nextEvent(Duration timeout) {
        Message m = rabbit.receive(CAPTURE, timeout.toMillis());
        assertThat(m).as("an event was published").isNotNull();
        JsonNode node = json.readTree(m.getBody());
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(node.get("eventId").asString());
        return node;
    }

    private void assertNoMoreEvents() {
        assertThat(rabbit.receive(CAPTURE, 700)).as("no further events").isNull();
    }

    private void sendPaymentEvent(String type, UUID eventId, UUID orderId, String extra) {
        String routingKey = type.equals("PaymentSucceeded") ? "payment.succeeded" : "payment.failed";
        String body = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T12:00:00Z\",\"producer\":\"payment-service\",\"data\":{\"orderId\":\""
                + orderId + "\"," + extra + "}}";
        sendRaw(routingKey, eventId.toString(), body);
    }

    private void sendRaw(String routingKey, String messageId, String body) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setContentType("application/json");
        rabbit.send(EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), p));
    }

    private static void eventually(Runnable assertion) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(assertion::run);
    }

    // =====================================================================================
    // happy path
    // =====================================================================================

    @Test
    void placesAnOrderAtCatalogPricesAndPublishesOrderPlaced() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "29.90", 10);
        var pen = STUB.product("PEN", "1.50", 100);
        STUB.cartLine(user, book, 2, "29.90");
        STUB.cartLine(user, pen, 3, "1.20"); // the cart showed an old price

        String body = place(user, "key-1").andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(64.30)) // 2*29.90 + 3*1.50, at CATALOG prices
                .andExpect(jsonPath("$.items[?(@.sku=='PEN')].unitPrice").value(1.50))
                .andExpect(jsonPath("$.items[?(@.sku=='PEN')].priceChanged").value(true))
                .andExpect(jsonPath("$.items[?(@.sku=='BOOK')].priceChanged").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(STUB.stock(book.id())).isEqualTo(8);
        assertThat(STUB.reservationStatus(orderId)).as("the order id is the reservation id").isEqualTo("RESERVED");
        assertThat(STUB.cartAuthorizations()).as("the cart was read with the user's own token").containsExactly(bearer(user));

        JsonNode event = nextEvent(Duration.ofSeconds(10));
        assertThat(event.get("eventType").asString()).isEqualTo("OrderPlaced");
        assertThat(event.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(event.get("producer").asString()).isEqualTo("order-service");
        JsonNode data = event.get("data");
        assertThat(data.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(data.get("userId").asString()).isEqualTo(user.toString());
        assertThat(data.get("totalAmount").isString()).as("money is a decimal string").isTrue();
        assertThat(data.get("totalAmount").asString()).isEqualTo("64.30");
        assertThat(data.get("items")).hasSize(2);
        assertNoMoreEvents();
        eventually(() -> assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL")).isEqualTo(1));

        mvc.perform(get("/api/orders/" + orderId).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void theServiceTokenIsFetchedOnceAndReusedAcrossOrders() throws Exception {
        tokens.invalidate(); // start from an empty cache (earlier tests share this application context)
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 100);
        STUB.cartLine(user, book, 1, "10.00");

        for (int i = 0; i < 4; i++) {
            placeOk(user);
        }

        assertThat(STUB.tokenRequests()).isEqualTo(1);
    }

    // =====================================================================================
    // idempotency of "place order"
    // =====================================================================================

    @Test
    void repeatingTheIdempotencyKeyReturnsTheSameOrderAndReservesOnce() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 10);
        STUB.cartLine(user, book, 2, "10.00");

        String first = place(user, "double-click").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = place(user, "double-click").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(STUB.reserveCalls()).isEqualTo(1);
        assertThat(STUB.stock(book.id())).isEqualTo(8);
        // another user may use the same key value independently
        UUID other = UUID.randomUUID();
        STUB.cartLine(other, book, 1, "10.00");
        place(other, "double-click").andExpect(status().isCreated());
    }

    @Test
    void theSameKeySentManyTimesAtOnceCreatesOneOrder() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 100);
        STUB.cartLine(user, book, 1, "10.00");
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(8, i -> statuses.add(place(user, "same-key").andReturn().getResponse().getStatus()));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409); // 409 = "still being placed"
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ? AND status <> 'FAILED'", user)).isEqualTo(1);
        assertThat(STUB.stock(book.id())).isEqualTo(99);
    }

    @Test
    void aMissingOrInvalidIdempotencyKeyIs400() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/api/orders").header("Authorization", bearer(user))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Authorization", bearer(user)).header("Idempotency-Key", "has spaces!"))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    // =====================================================================================
    // failure cases at placement
    // =====================================================================================

    @Test
    void anEmptyCartIs400AndNothingIsWritten() throws Exception {
        place(UUID.randomUUID(), "k").andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM orders")).isZero();
        assertThat(STUB.reserveCalls()).isZero();
    }

    @Test
    void cartServiceDownIs503AndNothingIsWritten() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 10), 1, "10.00");
        STUB.cartDown(true);

        place(user, "k").andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cart-service")));
        assertThat(count("SELECT count(*) FROM orders")).isZero();
        assertThat(STUB.reserveCalls()).isZero();
    }

    @Test
    void stockGoneMidOrderIs409WithShortages_nothingReserved_keyCanBeReused() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        var rare = STUB.product("RARE", "99.00", 1);
        STUB.cartLine(user, book, 2, "10.00");
        STUB.cartLine(user, rare, 2, "99.00"); // someone bought the other one meanwhile

        place(user, "checkout-1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.shortages[0].productId").value(rare.id().toString()))
                .andExpect(jsonPath("$.shortages[0].available").value(1))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cart is unchanged")));

        assertThat(STUB.stock(book.id())).as("all or nothing").isEqualTo(5);
        assertThat(count("SELECT count(*) FROM orders WHERE status = 'FAILED' AND failure_reason = 'OUT_OF_STOCK'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events")).as("no event for a failed attempt").isZero();
        mvc.perform(get("/api/orders").header("Authorization", bearer(user))).andExpect(jsonPath("$.totalElements").value(0));

        // restocked: the client retries with the SAME key and it now works
        STUB.setStock(rare.id(), 5);
        place(user, "checkout-1").andExpect(status().isCreated());
    }

    @Test
    void catalogDownIs503_theAttemptFails_andItsReservationIdIsReleased() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 1, "10.00");
        STUB.catalogMode(CatalogMode.ERROR_500, Duration.ZERO);

        place(user, "k").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Nothing was ordered")));

        UUID orderId = jdbc.queryForObject("SELECT id FROM orders", UUID.class);
        assertThat(orderStatus(orderId)).isEqualTo("FAILED");
        eventually(() -> assertThat(STUB.releaseCalls(orderId)).isGreaterThanOrEqualTo(1));
        eventually(() -> assertThat(count("SELECT count(*) FROM stock_releases WHERE completed_at IS NOT NULL")).isEqualTo(1));
        assertThat(STUB.reservationStatus(orderId)).as("tombstone: a late reserve would be refused").isEqualTo("RELEASED");
        assertThat(STUB.stock(book.id())).isEqualTo(5);

        STUB.catalogMode(CatalogMode.OK, Duration.ZERO);
        place(user, "k").andExpect(status().isCreated()); // same key works once the catalog is back
    }

    @Test
    void catalogReservedButAnsweredTooLate_theUnknownOutcomeIsReleased() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 2, "10.00");
        STUB.catalogMode(CatalogMode.RESERVE_THEN_STALL, Duration.ofSeconds(3)); // our read timeout is 1 s

        long start = System.nanoTime();
        place(user, "k").andExpect(status().isServiceUnavailable());
        assertThat((System.nanoTime() - start) / 1_000_000).as("failed fast at the timeout").isLessThan(2_500);
        assertThat(STUB.stock(book.id())).as("the catalog DID reserve").isEqualTo(3);

        UUID orderId = jdbc.queryForObject("SELECT id FROM orders", UUID.class);
        eventually(() -> assertThat(STUB.stock(book.id())).as("and the stock came back").isEqualTo(5));
        assertThat(orderStatus(orderId)).isEqualTo("FAILED");
    }

    @Test
    void crashBetweenReservingAndSaving_theReconcilerReleasesTheStock() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 2, "10.00");
        UUID orderId = placeOk(user);
        // Simulate a crash right after the catalog reserved: an order stuck in RESERVING, 3 minutes old.
        UUID stuck = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, user_id, status, idempotency_key, created_at, updated_at) "
                + "VALUES (?, ?, 'RESERVING', 'crashed', now() - interval '3 minutes', now() - interval '3 minutes')", stuck, user);
        assertThat(STUB.stock(book.id())).isEqualTo(3);

        eventually(() -> assertThat(orderStatus(stuck)).isEqualTo("FAILED"));
        eventually(() -> assertThat(STUB.releaseCalls(stuck)).isGreaterThanOrEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM orders WHERE id = ?", String.class, stuck)).isEqualTo("STUCK");
        assertThat(orderStatus(orderId)).as("healthy orders are untouched").isEqualTo("PENDING");
        // a fresh attempt is not touched by the reconciler
        assertThat(count("SELECT count(*) FROM orders WHERE status = 'RESERVING'")).isZero();
    }

    // =====================================================================================
    // the outbox
    // =====================================================================================

    @Test
    void whenTheBrokerRefusesEventsTheOrderIsStillPlacedAndTheEventGoesOutLater() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 5), 1, "10.00");
        admin.deleteExchange(EXCHANGE); // RabbitMQ now refuses everything sent to it

        UUID orderId = placeOk(user);

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        eventually(() -> assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND attempts > 0 "
                + "AND last_error IS NOT NULL")).isEqualTo(1));

        clean_restoreExchange();
        JsonNode event = nextEvent(Duration.ofSeconds(20));
        assertThat(event.get("data").get("orderId").asString()).isEqualTo(orderId.toString());
        eventually(() -> assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL")).isEqualTo(1));
    }

    private void clean_restoreExchange() {
        admin.initialize();
        Queue capture = new Queue(CAPTURE, true, false, false) /* RabbitMQ 4 removed transient non-exclusive queues */;
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture).to(new TopicExchange(EXCHANGE)).with("order.#"));
    }

    @Test
    void ifTheRelayRepublishesAfterACrashTheDuplicateCarriesTheSameEventId() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 5), 1, "10.00");
        placeOk(user);
        JsonNode first = nextEvent(Duration.ofSeconds(10));
        eventually(() -> assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL")).isEqualTo(1));

        // a crash after the broker confirmed but before the row was marked looks exactly like this:
        jdbc.update("UPDATE outbox_events SET published_at = NULL");

        JsonNode again = nextEvent(Duration.ofSeconds(10));
        assertThat(again.get("eventId").asString()).isEqualTo(first.get("eventId").asString());
    }

    // =====================================================================================
    // payment events
    // =====================================================================================

    @Test
    void paymentSucceeded_marksPaidAndPublishesOrderPaid_onceEvenIfDelivered3Times() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 5), 1, "10.00");
        UUID orderId = placeOk(user);
        nextEvent(Duration.ofSeconds(10)); // OrderPlaced
        UUID eventId = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            sendPaymentEvent("PaymentSucceeded", eventId, orderId, "\"paymentId\":\"pay-1\",\"amount\":\"10.00\"");
        }

        eventually(() -> assertThat(orderStatus(orderId)).isEqualTo("PAID"));
        JsonNode paid = nextEvent(Duration.ofSeconds(10));
        assertThat(paid.get("eventType").asString()).isEqualTo("OrderPaid");
        assertThat(paid.get("data").get("paymentId").asString()).isEqualTo("pay-1");
        assertNoMoreEvents();
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_id FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("pay-1");
    }

    @Test
    void paymentFailed_cancelsAndReleasesStockExactlyOnce_despiteDuplicatesAndASecondFailure() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 2, "10.00");
        UUID orderId = placeOk(user);
        nextEvent(Duration.ofSeconds(10));
        UUID eventId = UUID.randomUUID();

        sendPaymentEvent("PaymentFailed", eventId, orderId, "\"paymentId\":\"pay-1\",\"reason\":\"CARD_DECLINED\"");
        sendPaymentEvent("PaymentFailed", eventId, orderId, "\"paymentId\":\"pay-1\",\"reason\":\"CARD_DECLINED\""); // redelivery
        sendPaymentEvent("PaymentFailed", UUID.randomUUID(), orderId, "\"paymentId\":\"pay-2\",\"reason\":\"CARD_DECLINED\""); // a 2nd attempt failed too

        eventually(() -> assertThat(orderStatus(orderId)).isEqualTo("CANCELLED"));
        eventually(() -> assertThat(STUB.stock(book.id())).isEqualTo(5));
        JsonNode cancelled = nextEvent(Duration.ofSeconds(10));
        assertThat(cancelled.get("eventType").asString()).isEqualTo("OrderCancelled");
        assertThat(cancelled.get("data").get("reason").asString()).isEqualTo("PAYMENT_FAILED");
        assertNoMoreEvents();
        assertThat(count("SELECT count(*) FROM stock_releases WHERE order_id = ?", orderId)).isEqualTo(1);
        assertThat(STUB.releaseCalls(orderId)).isEqualTo(1);
        assertThat(STUB.stock(book.id())).as("released once, not three times").isEqualTo(5);
    }

    @Test
    void aPaymentThatSucceedsAfterTheCustomerCancelledLeavesTheOrderCancelled() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 5), 1, "10.00");
        UUID orderId = placeOk(user);
        nextEvent(Duration.ofSeconds(10));
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", bearer(user))).andExpect(status().isOk());
        nextEvent(Duration.ofSeconds(10)); // OrderCancelled -> payment-service refunds (contract)

        sendPaymentEvent("PaymentSucceeded", UUID.randomUUID(), orderId, "\"paymentId\":\"late\",\"amount\":\"10.00\"");

        Thread.sleep(1000);
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertNoMoreEvents(); // no OrderPaid
    }

    @Test
    void anEventForAnUnknownOrderIsRetriedThenDeadLettered() {
        UUID unknown = UUID.randomUUID();
        sendPaymentEvent("PaymentSucceeded", UUID.randomUUID(), unknown, "\"paymentId\":\"x\",\"amount\":\"1.00\"");

        Message dead = rabbit.receive(RabbitConfig.PAYMENT_EVENTS_DLQ, 15_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(unknown.toString());
        assertThat(count("SELECT count(*) FROM processed_events")).as("a failed effect is not marked processed").isZero();
    }

    @Test
    void aMalformedEventIsDeadLetteredWithoutRetrying() {
        sendRaw("payment.failed", "bad-1", "{ this is not json");

        Message dead = rabbit.receive(RabbitConfig.PAYMENT_EVENTS_DLQ, 10_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains("not json");
    }

    // =====================================================================================
    // cancel, timeout, stock release retries
    // =====================================================================================

    @Test
    void theCustomerCanCancelOnlyTheirOwnPendingOrder() throws Exception {
        UUID user = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 2, "10.00");
        UUID orderId = placeOk(user);
        nextEvent(Duration.ofSeconds(10));

        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("CUSTOMER_REQUEST"));
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", bearer(user)))
                .andExpect(status().isConflict());

        eventually(() -> assertThat(STUB.stock(book.id())).isEqualTo(5));
        assertThat(nextEvent(Duration.ofSeconds(10)).get("data").get("reason").asString()).isEqualTo("CUSTOMER_REQUEST");
    }

    @Test
    void aPaidOrderCannotBeCancelled() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 5), 1, "10.00");
        UUID orderId = placeOk(user);
        sendPaymentEvent("PaymentSucceeded", UUID.randomUUID(), orderId, "\"paymentId\":\"p\",\"amount\":\"10.00\"");
        eventually(() -> assertThat(orderStatus(orderId)).isEqualTo("PAID"));

        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PAID")));
    }

    @Test
    void anOrderNotPaidInTimeIsCancelledAndItsStockReleased() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 2, "10.00");
        UUID orderId = placeOk(user);
        nextEvent(Duration.ofSeconds(10));

        jdbc.update("UPDATE orders SET created_at = now() - interval '31 minutes' WHERE id = ?", orderId);

        eventually(() -> assertThat(orderStatus(orderId)).isEqualTo("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("PAYMENT_TIMEOUT");
        eventually(() -> assertThat(STUB.stock(book.id())).isEqualTo(5));
        assertThat(nextEvent(Duration.ofSeconds(10)).get("data").get("reason").asString()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    void aStockReleaseIsRetriedUntilTheCatalogConfirms() throws Exception {
        UUID user = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 5);
        STUB.cartLine(user, book, 2, "10.00");
        UUID orderId = placeOk(user);
        STUB.failNextReleases(2);

        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", bearer(user))).andExpect(status().isOk());

        eventually(() -> assertThat(count("SELECT count(*) FROM stock_releases WHERE completed_at IS NOT NULL")).isEqualTo(1));
        assertThat(STUB.releaseCalls(orderId)).isEqualTo(3);
        assertThat(count("SELECT attempts FROM stock_releases WHERE order_id = ?", orderId)).isEqualTo(2);
        assertThat(STUB.stock(book.id())).isEqualTo(5);
    }

    // =====================================================================================
    // reading orders, ownership, token separation
    // =====================================================================================

    @Test
    void customersSeeOnlyTheirOwnVisibleOrdersNewestFirst() throws Exception {
        UUID user = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        var book = STUB.product("BOOK", "10.00", 100);
        STUB.cartLine(user, book, 1, "10.00");
        STUB.cartLine(stranger, book, 1, "10.00");
        UUID first = placeOk(user);
        UUID second = placeOk(user);
        UUID theirs = placeOk(stranger);
        jdbc.update("INSERT INTO orders (id, user_id, status, created_at, updated_at) VALUES (?, ?, 'FAILED', now(), now())",
                UUID.randomUUID(), user);

        mvc.perform(get("/api/orders").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.items[1].id").value(first.toString()));
        mvc.perform(get("/api/orders/" + theirs).header("Authorization", bearer(user))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").param("size", "51").header("Authorization", bearer(user))).andExpect(status().isBadRequest());
    }

    @Test
    void serviceTokensAreRefusedOnEveryOrderEndpoint_andUsersNeedAValidToken() throws Exception {
        UUID user = UUID.randomUUID();
        STUB.cartLine(user, STUB.product("BOOK", "10.00", 5), 1, "10.00");
        UUID orderId = placeOk(user);
        String service = "Bearer " + JWKS.serviceToken("order-service", "product-catalog-service", List.of("stock:reserve"));
        String notAUser = "Bearer " + JWKS.userToken("not-a-uuid", "CUSTOMER");

        MockHttpServletRequestBuilder[] endpoints = {
                post("/api/orders").header("Idempotency-Key", "svc"),
                get("/api/orders"),
                get("/api/orders/" + orderId),
                post("/api/orders/" + orderId + "/cancel")};
        for (MockHttpServletRequestBuilder e : endpoints) {
            assertThat(mvc.perform(e.header("Authorization", service)).andReturn().getResponse().getStatus())
                    .as("service token").isEqualTo(403);
        }
        for (MockHttpServletRequestBuilder e : new MockHttpServletRequestBuilder[] {
                post("/api/orders").header("Idempotency-Key", "x"), get("/api/orders"), get("/api/orders/" + orderId),
                post("/api/orders/" + orderId + "/cancel")}) {
            assertThat(mvc.perform(e).andReturn().getResponse().getStatus()).as("no token").isEqualTo(401);
        }
        assertThat(mvc.perform(get("/api/orders").header("Authorization", notAUser)).andReturn().getResponse().getStatus())
                .as("subject is not a user id").isEqualTo(401);
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
    }

    // =====================================================================================
    // retention cleanup
    // =====================================================================================

    @Test
    void cleanupRemovesOldPublishedEventsAndProcessedIdsButNeverUnpublishedOnes() {
        UUID agg = UUID.randomUUID();
        String insert = "INSERT INTO outbox_events (id, aggregate_id, event_type, routing_key, payload, created_at, next_attempt_at, published_at) "
                + "VALUES (?, ?, 'OrderPlaced', 'order.placed', '{}'::jsonb, now(), now() + interval '1 day', ";
        UUID oldPublished = UUID.randomUUID();
        UUID recentPublished = UUID.randomUUID();
        UUID oldUnpublished = UUID.randomUUID();
        jdbc.update(insert + "now() - interval '8 days')", oldPublished, agg);
        jdbc.update(insert + "now() - interval '1 day')", recentPublished, agg);
        jdbc.update(insert + "NULL)", oldUnpublished, agg);
        jdbc.update("UPDATE outbox_events SET created_at = now() - interval '60 days' WHERE id = ?", oldUnpublished);
        UUID oldProcessed = UUID.randomUUID();
        UUID recentProcessed = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_events VALUES (?, 'PaymentSucceeded', now() - interval '31 days')", oldProcessed);
        jdbc.update("INSERT INTO processed_events VALUES (?, 'PaymentSucceeded', now() - interval '29 days')", recentProcessed);
        jdbc.update("INSERT INTO stock_releases (order_id, created_at, completed_at, next_attempt_at) VALUES (?, now(), now() - interval '31 days', now())", UUID.randomUUID());
        jdbc.update("INSERT INTO stock_releases (order_id, created_at, completed_at, next_attempt_at) VALUES (?, now() - interval '60 days', NULL, now() + interval '1 day')", UUID.randomUUID());

        cleanup.cleanUp();

        assertThat(jdbc.queryForList("SELECT id FROM outbox_events", UUID.class)).containsExactlyInAnyOrder(recentPublished, oldUnpublished);
        assertThat(jdbc.queryForList("SELECT event_id FROM processed_events", UUID.class)).containsExactly(recentProcessed);
        assertThat(count("SELECT count(*) FROM stock_releases")).as("pending releases are kept").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM stock_releases WHERE completed_at IS NULL")).isEqualTo(1);
    }

    // =====================================================================================

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
