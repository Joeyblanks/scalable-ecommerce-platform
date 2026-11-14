package io.github.joeyblanks.ecommerce.cart.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository;
import io.github.joeyblanks.ecommerce.cart.support.CatalogStub;
import io.github.joeyblanks.ecommerce.cart.support.TestJwks;
import java.time.Duration;
import java.time.Instant;
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

/**
 * Full-stack tests: real Spring context, real security chain verifying real RS256 tokens against a
 * JWKS served over HTTP, a real Redis in a container, and a stub catalog over real HTTP.
 * Run by Failsafe; requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CartApiIT {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    static final TestJwks JWKS = new TestJwks();
    static final CatalogStub CATALOG = new CatalogStub();

    static final Duration TTL = Duration.ofMinutes(30);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwk-set-uri", JWKS::url);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
        registry.add("app.catalog.base-url", CATALOG::url);
        registry.add("app.catalog.connect-timeout", () -> "1s");
        registry.add("app.catalog.read-timeout", () -> "1s");
        registry.add("app.cart.ttl", TTL::toString);
        // With N simultaneous writers on one line, a writer loses at most N-1 times, so N attempts always suffice.
        registry.add("app.cart.cas-max-attempts", () -> "25");
    }

    @AfterAll
    static void stopStubs() {
        JWKS.close();
        CATALOG.close();
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        CATALOG.reset();
    }

    // ---------- helpers ----------

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static String bearer(UUID user) {
        return "Bearer " + JWKS.token(user.toString(), "CUSTOMER");
    }

    private static String addBody(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private ResultActions add(UUID user, UUID product, int quantity) throws Exception {
        return mvc.perform(post("/api/cart/items").header("Authorization", bearer(user)).contentType(JSON)
                .content(addBody(product, quantity)));
    }

    private ResultActions setQuantity(UUID user, UUID product, int quantity) throws Exception {
        return mvc.perform(put("/api/cart/items/" + product).header("Authorization", bearer(user)).contentType(JSON)
                .content("{\"quantity\":" + quantity + "}"));
    }

    private ResultActions view(UUID user) throws Exception {
        return mvc.perform(get("/api/cart").header("Authorization", bearer(user)));
    }

    private boolean cartExists(UUID user) {
        return Boolean.TRUE.equals(redis.hasKey(CartRepository.key(user)));
    }

    // ---------- the normal flow ----------

    @Test
    void addViewChangeRemoveAndClear() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        var toy = CATALOG.put("TOY-1", "Toy Car", "4.50", 50);

        view(user).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0.0));

        add(user, book.id(), 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("CC-1"))
                .andExpect(jsonPath("$.items[0].name").value("Clean Code"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(29.90))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(59.80))
                .andExpect(jsonPath("$.total").value(59.80));
        // adding again increases the same line
        add(user, book.id(), 1).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
        add(user, toy.id(), 4).andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.totalQuantity").value(7))
                .andExpect(jsonPath("$.total").value(107.70)); // 3 * 29.90 + 4 * 4.50

        setQuantity(user, book.id(), 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.sku=='CC-1')].quantity").value(1))
                .andExpect(jsonPath("$.total").value(47.90)); // 1 * 29.90 + 4 * 4.50

        mvc.perform(delete("/api/cart/items/" + book.id()).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("TOY-1"));

        mvc.perform(delete("/api/cart").header("Authorization", bearer(user))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/cart").header("Authorization", bearer(user))).andExpect(status().isNoContent()); // idempotent
        view(user).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(cartExists(user)).isFalse();
    }

    @Test
    void theCartIsStoredAsOneRedisHashPerUser() throws Exception {
        UUID user = UUID.randomUUID();
        var a = CATALOG.put("A", "A", "1.00", 10);
        var b = CATALOG.put("B", "B", "2.00", 10);
        add(user, a.id(), 1).andExpect(status().isOk());
        add(user, b.id(), 1).andExpect(status().isOk());

        assertThat(redis.type("cart:" + user).code()).isEqualTo("hash");
        assertThat(redis.opsForHash().size("cart:" + user)).isEqualTo(2L);
        assertThat((String) redis.opsForHash().get("cart:" + user, a.id().toString())).contains("\"sku\":\"A\"");
    }

    @Test
    void eachUserOnlyEverSeesAndChangesTheirOwnCart() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);

        add(alice, book.id(), 5).andExpect(status().isOk());

        view(bob).andExpect(jsonPath("$.items.length()").value(0));
        add(bob, book.id(), 1).andExpect(jsonPath("$.items[0].quantity").value(1));
        setQuantity(bob, book.id(), 2).andExpect(jsonPath("$.items[0].quantity").value(2));
        mvc.perform(delete("/api/cart").header("Authorization", bearer(bob))).andExpect(status().isNoContent());

        view(alice).andExpect(jsonPath("$.items[0].quantity").value(5)); // untouched by Bob
        // a user id passed in the request is ignored; only the token decides
        mvc.perform(get("/api/cart").param("userId", alice.toString()).header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ---------- authentication ----------

    @Test
    void missingForgedExpiredAndMalformedTokensAreRejectedEverywhere() throws Exception {
        UUID product = UUID.randomUUID();
        var attacker = TestJwks.generate("trusted-key"); // same kid as the real key, different key material
        UUID user = UUID.randomUUID();
        String forged = "Bearer " + JWKS.sign(attacker, TestJwks.ISSUER, user.toString(), "CUSTOMER",
                Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        String expired = "Bearer " + JWKS.sign(TestJwks.generate("trusted-key"), TestJwks.ISSUER, user.toString(), "CUSTOMER",
                Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1)));
        String wrongIssuer = "Bearer " + JWKS.sign(attacker, "evil-issuer", user.toString(), "CUSTOMER",
                Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        String notAUserId = "Bearer " + JWKS.token("../cart:someone-else", "CUSTOMER");

        for (String auth : new String[] {null, "Bearer not.a.jwt", forged, expired, wrongIssuer, notAUserId}) {
            mvc.perform(withAuth(get("/api/cart"), auth)).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(post("/api/cart/items"), auth).contentType(JSON).content(addBody(product, 1))).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(put("/api/cart/items/" + product), auth).contentType(JSON).content("{\"quantity\":1}")).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(delete("/api/cart/items/" + product), auth)).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(delete("/api/cart"), auth)).andExpect(status().isUnauthorized());
        }
        assertThat(redis.keys("cart:*")).isEmpty();
        assertThat(CATALOG.hits()).isZero();
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b, String authorization) {
        return authorization == null ? b : b.header("Authorization", authorization);
    }

    @Test
    void adminsAndRolelessTokensGetTheirOwnCartToo() throws Exception {
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        for (String role : new String[] {"ADMIN", "CUSTOMER", null}) {
            UUID user = UUID.randomUUID();
            mvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + JWKS.token(user.toString(), role))
                            .contentType(JSON).content(addBody(book.id(), 1)))
                    .andExpect(status().isOk());
        }
    }

    // ---------- catalog validation ----------

    @Test
    void unknownProductIs404AndNothingIsStored() throws Exception {
        UUID user = UUID.randomUUID();

        add(user, UUID.randomUUID(), 1).andExpect(status().isNotFound());
        assertThat(cartExists(user)).isFalse();
    }

    @Test
    void notEnoughStockIs409WithTheAvailableQuantityCountingWhatIsAlreadyInTheCart() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 3);

        add(user, book.id(), 2).andExpect(status().isOk());
        add(user, book.id(), 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.availableStock").value(3))
                .andExpect(jsonPath("$.requestedTotal").value(4));
        setQuantity(user, book.id(), 4).andExpect(status().isConflict()).andExpect(jsonPath("$.availableStock").value(3));
        add(user, book.id(), 1).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(3)); // exactly the stock

        view(user).andExpect(jsonPath("$.items[0].quantity").value(3)); // rejected requests changed nothing
    }

    @Test
    void outOfStockProductCannotBeAdded() throws Exception {
        var gone = CATALOG.put("GONE", "Sold out", "9.99", 0);

        add(UUID.randomUUID(), gone.id(), 1).andExpect(status().isConflict()).andExpect(jsonPath("$.availableStock").value(0));
    }

    @Test
    void updatingOrRemovingALineThatIsNotInTheCartIs404WithoutAskingTheCatalog() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);

        setQuantity(user, book.id(), 2).andExpect(status().isNotFound());
        mvc.perform(delete("/api/cart/items/" + book.id()).header("Authorization", bearer(user))).andExpect(status().isNotFound());
        assertThat(CATALOG.hits()).isZero();
    }

    @Test
    void invalidInputIs400() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 5000);

        add(user, book.id(), 0).andExpect(status().isBadRequest());
        add(user, book.id(), -1).andExpect(status().isBadRequest());
        add(user, book.id(), 100).andExpect(status().isBadRequest()); // above the per-line limit of 99
        mvc.perform(post("/api/cart/items").header("Authorization", bearer(user)).contentType(JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cart/items").header("Authorization", bearer(user)).contentType(JSON).content("garbage"))
                .andExpect(status().isBadRequest());
        assertThat(cartExists(user)).isFalse();
    }

    // ---------- catalog failures ----------

    @Test
    void whenTheCatalogErrorsTheAddFailsClearlyAndTheCartIsUnchanged() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        add(user, book.id(), 2).andExpect(status().isOk());

        for (CatalogStub.Mode mode : new CatalogStub.Mode[] {CatalogStub.Mode.ERROR_500, CatalogStub.Mode.GARBAGE, CatalogStub.Mode.EMPTY_BODY}) {
            CATALOG.mode(mode);
            add(user, book.id(), 1).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("catalog is temporarily unavailable")));
            setQuantity(user, book.id(), 9).andExpect(status().isServiceUnavailable());
        }
        CATALOG.mode(CatalogStub.Mode.OK);
        view(user).andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void aSlowCatalogGivesAQuick503InsteadOfHanging() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        CATALOG.delay(Duration.ofSeconds(4)); // the cart's read timeout is 1 s

        long start = System.nanoTime();
        add(user, book.id(), 1).andExpect(status().isServiceUnavailable());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("gave up near the 1 s timeout, not after the catalog's 4 s").isLessThan(3_000);
        assertThat(cartExists(user)).isFalse();
    }

    @Test
    void viewingTheCartWorksWhileTheCatalogIsDownAndNeverCallsIt() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        add(user, book.id(), 2).andExpect(status().isOk());
        CATALOG.mode(CatalogStub.Mode.ERROR_500);
        CATALOG.delay(Duration.ofSeconds(4));
        int hitsBefore = CATALOG.hits();

        long start = System.nanoTime();
        view(user).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(2));
        mvc.perform(delete("/api/cart/items/" + book.id()).header("Authorization", bearer(user))).andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(CATALOG.hits()).isEqualTo(hitsBefore);
        assertThat(elapsedMs).isLessThan(1_000);
    }

    // ---------- price snapshot ----------

    @Test
    void theCartShowsTheSnapshotPriceAndRefreshesItWhenTheLineIsChanged() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "10.00", 50);
        add(user, book.id(), 2).andExpect(jsonPath("$.total").value(20.00));

        CATALOG.update(new CatalogStub.Product(book.id(), "CC-1", "Clean Code", new java.math.BigDecimal("12.50"), 50));
        int hits = CATALOG.hits();
        view(user).andExpect(jsonPath("$.items[0].unitPrice").value(10.00)).andExpect(jsonPath("$.total").value(20.00));
        assertThat(CATALOG.hits()).as("viewing must not ask the catalog").isEqualTo(hits);

        setQuantity(user, book.id(), 2).andExpect(jsonPath("$.items[0].unitPrice").value(12.50)).andExpect(jsonPath("$.total").value(25.00));
    }

    // ---------- limits ----------

    @Test
    void perLineAndPerCartLimitsAreEnforced() throws Exception {
        UUID user = UUID.randomUUID();
        var big = CATALOG.put("BIG", "Big stock", "1.00", 10_000);
        add(user, big.id(), 99).andExpect(status().isOk());
        add(user, big.id(), 1).andExpect(status().isConflict()); // would be 100 > 99

        // 49 more different products fill the cart (50 lines); the 51st is refused
        for (int i = 1; i <= 49; i++) {
            add(user, CATALOG.put("P" + i, "Product " + i, "1.00", 10).id(), 1).andExpect(status().isOk());
        }
        add(user, CATALOG.put("ONE-TOO-MANY", "Extra", "1.00", 10).id(), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("50")));
        // existing lines can still be changed when the cart is full
        setQuantity(user, big.id(), 5).andExpect(status().isOk());
        assertThat(redis.opsForHash().size("cart:" + user)).isEqualTo(50L);
    }

    // ---------- expiry of inactive carts ----------

    @Test
    void theCartExpiresAfterInactivityAndEveryAccessRenewsTheTimer() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        String key = CartRepository.key(user);

        add(user, book.id(), 1).andExpect(status().isOk());
        assertThat(redis.getExpire(key)).as("a TTL is set on a new cart").isBetween(1700L, 1800L);

        redis.expire(key, Duration.ofSeconds(100));            // pretend the cart has been idle for a while
        view(user).andExpect(status().isOk());                   // viewing is activity
        assertThat(redis.getExpire(key)).as("viewing renewed the TTL").isGreaterThan(1500L);

        redis.expire(key, Duration.ofSeconds(100));
        add(user, book.id(), 1).andExpect(status().isOk());      // changing is activity
        assertThat(redis.getExpire(key)).as("changing renewed the TTL").isGreaterThan(1500L);

        redis.expire(key, Duration.ofSeconds(100));
        setQuantity(user, book.id(), 3).andExpect(status().isOk());
        assertThat(redis.getExpire(key)).isGreaterThan(1500L);

        redis.expire(key, Duration.ofSeconds(1));                // now really let it expire
        Thread.sleep(1500);
        view(user).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(cartExists(user)).isFalse();
    }

    // ---------- concurrency: the lost-update protection ----------

    @Test
    void twentySimultaneousAddsOfTheSameProductAreAllCounted() throws Exception {
        UUID user = UUID.randomUUID();
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 1000);
        int threads = 20;

        runConcurrently(threads, i -> add(user, book.id(), 1).andExpect(status().isOk()));

        view(user).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(threads))
                .andExpect(jsonPath("$.totalQuantity").value(threads));
    }

    @Test
    void simultaneousAddsOfDifferentProductsAllLand() throws Exception {
        UUID user = UUID.randomUUID();
        List<CatalogStub.Product> products = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            products.add(CATALOG.put("P" + i, "Product " + i, "1.00", 10));
        }

        runConcurrently(products.size(), i -> add(user, products.get(i).id(), 2).andExpect(status().isOk()));

        view(user).andExpect(jsonPath("$.itemCount").value(12)).andExpect(jsonPath("$.totalQuantity").value(24));
    }

    @Test
    void simultaneousAddsNeverPushTheCartPastTheStock() throws Exception {
        UUID user = UUID.randomUUID();
        var scarce = CATALOG.put("RARE", "Rare", "99.00", 5);
        int threads = 12;
        List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<>());

        runConcurrently(threads, i -> statuses.add(add(user, scarce.id(), 1).andReturn().getResponse().getStatus()));

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 5);
        view(user).andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    private interface Task {
        void run(int index) throws Exception;
    }

    /** Starts all tasks at the same moment from separate threads and fails if any of them failed. */
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
            f.get(30, TimeUnit.SECONDS); // rethrows any assertion failure from the worker
        }
        pool.shutdownNow();
    }
}
