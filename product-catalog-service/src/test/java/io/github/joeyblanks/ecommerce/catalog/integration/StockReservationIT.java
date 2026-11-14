package io.github.joeyblanks.ecommerce.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.joeyblanks.ecommerce.catalog.repository.CategoryRepository;
import io.github.joeyblanks.ecommerce.catalog.repository.ProductRepository;
import io.github.joeyblanks.ecommerce.catalog.support.TestJwks;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Internal stock reservations on the real application and a real PostgreSQL: atomicity,
 * idempotency, tombstones, concurrency, and the separation of service and user tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StockReservationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static final TestJwks JWKS = new TestJwks();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwk-set-uri", JWKS::url);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String AUDIENCE = "product-catalog-service";
    private String category;

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM stock_reservation_items");
        jdbc.update("DELETE FROM stock_reservations");
        products.deleteAll();
        categories.deleteAll();
        category = JsonPath.read(mvc.perform(post("/api/categories").header("Authorization", admin()).contentType(JSON)
                        .content("{\"name\":\"Books\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    // ---------- helpers ----------

    private static String admin() {
        return "Bearer " + JWKS.token("ADMIN");
    }

    private static String service() {
        return "Bearer " + JWKS.serviceToken("order-service", AUDIENCE, List.of("stock:reserve"));
    }

    private String product(String sku, String price, int stock) throws Exception {
        String body = mvc.perform(post("/api/products").header("Authorization", admin()).contentType(JSON)
                        .content("{\"sku\":\"" + sku + "\",\"name\":\"" + sku + " name\",\"price\":" + price
                                + ",\"stockQuantity\":" + stock + ",\"categoryId\":\"" + category + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private int stockOf(String productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    private long versionOf(String productId) {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?::uuid", Long.class, productId);
    }

    private static String reserveBody(UUID id, String... productAndQuantity) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":\"").append(productAndQuantity[i]).append("\",\"quantity\":")
                    .append(productAndQuantity[i + 1]).append('}');
        }
        return "{\"reservationId\":\"" + id + "\",\"items\":[" + items + "]}";
    }

    private ResultActions reserve(UUID id, String... productAndQuantity) throws Exception {
        return mvc.perform(post("/internal/stock/reservations").header("Authorization", service()).contentType(JSON)
                .content(reserveBody(id, productAndQuantity)));
    }

    private ResultActions release(UUID id) throws Exception {
        return mvc.perform(post("/internal/stock/reservations/" + id + "/release").header("Authorization", service()));
    }

    // ---------- reserving ----------

    @Test
    void reservesAllItemsAndReturnsTheAuthoritativePrices() throws Exception {
        String a = product("A", "10.00", 5);
        String b = product("B", "2.50", 10);
        long versionBefore = versionOf(a);
        UUID id = UUID.randomUUID();

        reserve(id, a, "2", b, "3").andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.sku=='A')].unitPrice").value(10.00))
                .andExpect(jsonPath("$.items[?(@.sku=='A')].name").value("A name"))
                .andExpect(jsonPath("$.items[?(@.sku=='B')].lineTotal").value(7.50))
                .andExpect(jsonPath("$.total").value(27.50));

        assertThat(stockOf(a)).isEqualTo(3);
        assertThat(stockOf(b)).isEqualTo(7);
        assertThat(versionOf(a)).as("a reservation bumps the product version").isGreaterThan(versionBefore);
    }

    @Test
    void repeatingTheSameReservationReturnsTheOriginalResultAndTakesNoMoreStock() throws Exception {
        String a = product("A", "10.00", 5);
        UUID id = UUID.randomUUID();
        reserve(id, a, "2").andExpect(status().isCreated());
        // the admin changes the price afterwards; the reservation keeps the price it was made at
        mvc.perform(put("/api/products/" + a).header("Authorization", admin()).contentType(JSON)
                        .content("{\"sku\":\"A\",\"name\":\"A name\",\"price\":99.00,\"stockQuantity\":3,\"categoryId\":\""
                                + category + "\",\"version\":" + versionOf(a) + "}"))
                .andExpect(status().isOk());

        reserve(id, a, "2").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(10.00));
        reserve(id, a, "2").andExpect(status().isOk());

        assertThat(stockOf(a)).isEqualTo(3);
    }

    @Test
    void reusingAnIdForDifferentItemsIsAConflict() throws Exception {
        String a = product("A", "10.00", 5);
        String b = product("B", "1.00", 5);
        UUID id = UUID.randomUUID();
        reserve(id, a, "2").andExpect(status().isCreated());

        reserve(id, a, "3").andExpect(status().isConflict());
        reserve(id, a, "2", b, "1").andExpect(status().isConflict());
        assertThat(stockOf(a)).isEqualTo(3);
        assertThat(stockOf(b)).isEqualTo(5);
    }

    @Test
    void itIsAllOrNothing_oneShortItemReservesNothingAndListsWhatIsShort() throws Exception {
        String a = product("A", "10.00", 5);
        String b = product("B", "1.00", 1);
        String c = product("C", "1.00", 0);
        String missing = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();

        reserve(id, a, "2", b, "2", c, "1", missing, "1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.shortages.length()").value(3))
                .andExpect(jsonPath("$.shortages[?(@.productId=='" + b + "')].available").value(1))
                .andExpect(jsonPath("$.shortages[?(@.productId=='" + b + "')].requested").value(2))
                .andExpect(jsonPath("$.shortages[?(@.productId=='" + b + "')].reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.shortages[?(@.productId=='" + c + "')].available").value(0))
                .andExpect(jsonPath("$.shortages[?(@.productId=='" + missing + "')].reason").value("PRODUCT_NOT_FOUND"));

        assertThat(stockOf(a)).as("A had enough but must not be touched").isEqualTo(5);
        assertThat(stockOf(b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservations", Integer.class)).isZero();
        // and the same id can still be used for a reservation that fits
        reserve(id, a, "2").andExpect(status().isCreated());
    }

    @Test
    void invalidReservationRequestsAre400() throws Exception {
        String a = product("A", "10.00", 5);
        String[] bodies = {
                "{\"reservationId\":\"" + UUID.randomUUID() + "\",\"items\":[]}",
                "{\"items\":[{\"productId\":\"" + a + "\",\"quantity\":1}]}",
                reserveBody(UUID.randomUUID(), a, "0"),
                reserveBody(UUID.randomUUID(), a, "-1"),
                reserveBody(UUID.randomUUID(), a, "1", a, "2"), // the same product twice
                "not json"};
        for (String body : bodies) {
            mvc.perform(post("/internal/stock/reservations").header("Authorization", service()).contentType(JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(stockOf(a)).isEqualTo(5);
    }

    // ---------- releasing ----------

    @Test
    void releaseReturnsTheStockExactlyOnce() throws Exception {
        String a = product("A", "10.00", 5);
        String b = product("B", "1.00", 5);
        UUID id = UUID.randomUUID();
        reserve(id, a, "2", b, "4").andExpect(status().isCreated());

        release(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        release(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        release(id).andExpect(status().isOk());

        assertThat(stockOf(a)).isEqualTo(5);
        assertThat(stockOf(b)).isEqualTo(5);
    }

    @Test
    void releasingAnUnknownIdLeavesATombstoneSoALateReserveIsRefused() throws Exception {
        String a = product("A", "10.00", 5);
        UUID id = UUID.randomUUID();

        release(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        reserve(id, a, "2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already released")));

        assertThat(stockOf(a)).isEqualTo(5);
    }

    @Test
    void aReleasedReservationCannotBeReservedAgain() throws Exception {
        String a = product("A", "10.00", 5);
        UUID id = UUID.randomUUID();
        reserve(id, a, "2").andExpect(status().isCreated());
        release(id).andExpect(status().isOk());

        reserve(id, a, "2").andExpect(status().isConflict());
        assertThat(stockOf(a)).isEqualTo(5);
    }

    @Test
    void releaseStillSucceedsWhenAProductWasDeletedMeanwhile() throws Exception {
        String a = product("A", "10.00", 5);
        String b = product("B", "1.00", 5);
        UUID id = UUID.randomUUID();
        reserve(id, a, "1", b, "1").andExpect(status().isCreated());
        mvc.perform(delete("/api/products/" + b).header("Authorization", admin())).andExpect(status().isNoContent());

        release(id).andExpect(status().isOk());

        assertThat(stockOf(a)).isEqualTo(5);
    }

    // ---------- admin edits cannot overwrite reserved stock ----------

    @Test
    void anAdminEditBasedOnAStaleReadIsRefusedAfterAReservation() throws Exception {
        String a = product("A", "10.00", 5);
        long adminReadVersion = versionOf(a);             // the admin opens the edit form: stock 5
        reserve(UUID.randomUUID(), a, "2").andExpect(status().isCreated()); // an order takes 2 meanwhile

        // the admin only meant to change the price, but the form still says stock 5
        String staleEdit = "{\"sku\":\"A\",\"name\":\"A name\",\"price\":12.00,\"stockQuantity\":5,\"categoryId\":\""
                + category + "\",\"version\":" + adminReadVersion + "}";
        mvc.perform(put("/api/products/" + a).header("Authorization", admin()).contentType(JSON).content(staleEdit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("changed since you read it")));
        assertThat(stockOf(a)).as("the reserved units were not handed back by the stale edit").isEqualTo(3);

        // after reloading, the edit goes through with the real stock
        String freshEdit = staleEdit.replace("\"stockQuantity\":5", "\"stockQuantity\":3")
                .replace("\"version\":" + adminReadVersion, "\"version\":" + versionOf(a));
        mvc.perform(put("/api/products/" + a).header("Authorization", admin()).contentType(JSON).content(freshEdit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(12.00));
    }

    @Test
    void aPutWithoutVersionIsRejected() throws Exception {
        String a = product("A", "10.00", 5);

        mvc.perform(put("/api/products/" + a).header("Authorization", admin()).contentType(JSON)
                        .content("{\"sku\":\"A\",\"name\":\"A\",\"price\":1,\"stockQuantity\":5,\"categoryId\":\"" + category + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("version is required")));
    }

    // ---------- concurrency ----------

    @Test
    void competingOrdersForTheLastUnits_exactlyTheAvailableStockSucceeds() throws Exception {
        String a = product("RARE", "99.00", 5);
        int orders = 12;
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(orders, i -> statuses.add(reserve(UUID.randomUUID(), a, "1").andReturn().getResponse().getStatus()));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(orders - 5);
        assertThat(stockOf(a)).isZero();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM stock_reservation_items", Integer.class)).isEqualTo(5);
    }

    @Test
    void multiItemOrdersInOppositeItemOrderDoNotDeadlock() throws Exception {
        String a = product("A", "1.00", 100);
        String b = product("B", "1.00", 100);
        int orders = 20;
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(orders, i -> {
            UUID id = UUID.randomUUID();
            ResultActions r = i % 2 == 0 ? reserve(id, a, "1", b, "1") : reserve(id, b, "1", a, "1");
            statuses.add(r.andReturn().getResponse().getStatus());
        });

        assertThat(statuses).as("no deadlock errors, every order reserved").containsOnly(201).hasSize(orders);
        assertThat(stockOf(a)).isEqualTo(80);
        assertThat(stockOf(b)).isEqualTo(80);
    }

    @Test
    void theSameReservationSentManyTimesAtOnceTakesStockOnce() throws Exception {
        String a = product("A", "1.00", 50);
        UUID id = UUID.randomUUID();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(10, i -> statuses.add(reserve(id, a, "3").andReturn().getResponse().getStatus()));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(9);
        assertThat(stockOf(a)).isEqualTo(47);
    }

    // ---------- token separation, both ways ----------

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b, String authorization) {
        return authorization == null ? b : b.header("Authorization", authorization);
    }

    @Test
    void userAndAdminTokensAreRefusedOnInternalEndpoints() throws Exception {
        String a = product("A", "1.00", 5);
        UUID id = UUID.randomUUID();
        var attacker = TestJwks.generate("trusted-key");
        String[][] cases = {
                {"no token", null, "401"},
                {"garbage", "Bearer not.a.jwt", "401"},
                {"customer", "Bearer " + JWKS.token("CUSTOMER"), "403"},
                {"admin", "Bearer " + JWKS.token("ADMIN"), "403"},
                {"service, no scope", "Bearer " + JWKS.serviceToken("order-service", AUDIENCE, null), "403"},
                {"service, other scope", "Bearer " + JWKS.serviceToken("order-service", AUDIENCE, List.of("reports:read")), "403"},
                {"service, other audience", "Bearer " + JWKS.serviceToken("order-service", "cart-service", List.of("stock:reserve")), "403"},
                {"service, no audience", "Bearer " + JWKS.serviceToken("order-service", null, List.of("stock:reserve")), "403"},
                {"scope but not a service token (no client_id)", "Bearer " + JWKS.signClaims(c -> c.subject("x")
                        .audience(List.of(AUDIENCE)).claim("scope", List.of("stock:reserve")).claim("roles", List.of("ADMIN"))), "403"},
                {"forged service token", "Bearer " + JWKS.serviceToken(attacker, "order-service", AUDIENCE, List.of("stock:reserve")), "401"},
        };
        for (String[] c : cases) {
            int reserveStatus = mvc.perform(withAuth(post("/internal/stock/reservations"), c[1]).contentType(JSON)
                    .content(reserveBody(id, a, "1"))).andReturn().getResponse().getStatus();
            int releaseStatus = mvc.perform(withAuth(post("/internal/stock/reservations/" + id + "/release"), c[1]))
                    .andReturn().getResponse().getStatus();
            assertThat(reserveStatus).as("reserve: " + c[0]).isEqualTo(Integer.parseInt(c[2]));
            assertThat(releaseStatus).as("release: " + c[0]).isEqualTo(Integer.parseInt(c[2]));
        }
        assertThat(stockOf(a)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservations", Integer.class)).isZero();
        // and the real service token does work
        reserve(id, a, "1").andExpect(status().isCreated());
    }

    @Test
    void serviceTokensAreRefusedOnEveryCustomerFacingEndpoint() throws Exception {
        String a = product("A", "1.00", 5);
        String service = service();
        String productBody = "{\"sku\":\"Z\",\"name\":\"Z\",\"price\":1,\"stockQuantity\":1,\"categoryId\":\"" + category
                + "\",\"version\":" + versionOf(a) + "}";
        MockHttpServletRequestBuilder[] customerEndpoints = {
                get("/api/products"),
                get("/api/products/" + a),
                get("/api/categories"),
                get("/api/categories/" + category),
                post("/api/products").contentType(JSON).content(productBody),
                put("/api/products/" + a).contentType(JSON).content(productBody),
                delete("/api/products/" + a),
                post("/api/categories").contentType(JSON).content("{\"name\":\"New\"}"),
                put("/api/categories/" + category).contentType(JSON).content("{\"name\":\"Renamed\"}"),
                delete("/api/categories/" + category)};
        for (MockHttpServletRequestBuilder endpoint : customerEndpoints) {
            int status = mvc.perform(endpoint.header("Authorization", service)).andReturn().getResponse().getStatus();
            assertThat(status).as("service token on a customer endpoint").isEqualTo(403);
        }
        assertThat(products.count()).isEqualTo(1);
        assertThat(stockOf(a)).isEqualTo(5);
        // the same reads stay public for anonymous callers and users
        mvc.perform(get("/api/products")).andExpect(status().isOk());
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + JWKS.token("CUSTOMER"))).andExpect(status().isOk());
        // infrastructure stays reachable for services
        mvc.perform(get("/actuator/health").header("Authorization", service)).andExpect(status().isOk());
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
