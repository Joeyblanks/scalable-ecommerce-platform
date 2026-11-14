package io.github.joeyblanks.ecommerce.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.joeyblanks.ecommerce.catalog.repository.CategoryRepository;
import io.github.joeyblanks.ecommerce.catalog.repository.ProductRepository;
import io.github.joeyblanks.ecommerce.catalog.support.TestJwks;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * Full-stack tests: real Spring context, real security chain verifying real RS256 tokens against a
 * JWKS served over HTTP, Flyway, and PostgreSQL in a container. Run by Failsafe; requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogApiIT {

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

    @BeforeEach
    void clean() {
        products.deleteAll();
        categories.deleteAll();
    }

    // ---------- helpers ----------

    private String admin() {
        return "Bearer " + JWKS.token("ADMIN");
    }

    private String customer() {
        return "Bearer " + JWKS.token("CUSTOMER");
    }

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private String createCategory(String name) throws Exception {
        String body = mvc.perform(post("/api/categories").header("Authorization", admin()).contentType(JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"d\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** For updates: the same body plus the version the caller read. */
    private static String productJson(String sku, String name, String price, int stock, String categoryId, long version) {
        String body = productJson(sku, name, price, stock, categoryId);
        return body.substring(0, body.length() - 1) + ",\"version\":" + version + "}";
    }

    private long versionOf(String productId) throws Exception {
        String body = mvc.perform(get("/api/products/" + productId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.version")).longValue();
    }

    private static String productJson(String sku, String name, String price, int stock, String categoryId) {
        return "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"price\":" + price + ",\"stockQuantity\":" + stock
                + ",\"categoryId\":\"" + categoryId + "\"}";
    }

    private ResultActions createProduct(String sku, String name, String price, int stock, String categoryId) throws Exception {
        return mvc.perform(post("/api/products").header("Authorization", admin()).contentType(JSON)
                .content(productJson(sku, name, price, stock, categoryId)));
    }

    private String createProductId(String sku, String name, String price, int stock, String categoryId) throws Exception {
        String body = createProduct(sku, name, price, stock, categoryId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    // ---------- schema ----------

    @Test
    void flywayMigrationRanAgainstRealDatabase() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success = true", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void databaseRejectsNegativeStockAndPrice() {
        String cat = "'" + java.util.UUID.randomUUID() + "'";
        jdbc.update("insert into categories(id,name) values (" + cat + ",'c')");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into products(id,sku,name,price,stock_quantity,category_id) values (gen_random_uuid(),'A','n',1,-1," + cat + ")"))
                .isInstanceOf(Exception.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into products(id,sku,name,price,stock_quantity,category_id) values (gen_random_uuid(),'B','n',-1,1," + cat + ")"))
                .isInstanceOf(Exception.class);
    }

    // ---------- public reads ----------

    @Test
    void anonymousCanReadEmptyListsWithPageShape() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        mvc.perform(get("/api/categories")).andExpect(status().isOk());
    }

    @Test
    void anonymousCanReadWhatAdminCreated() throws Exception {
        String cat = createCategory("Books");
        String id = createProductId("cc-1", "Clean Code", "29.90", 7, cat);

        mvc.perform(get("/api/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("CC-1"))
                .andExpect(jsonPath("$.price").value(29.90))
                .andExpect(jsonPath("$.stockQuantity").value(7))
                .andExpect(jsonPath("$.categoryName").value("Books"));
        mvc.perform(get("/api/categories/" + cat)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Books"));
    }

    @Test
    void unknownIdsAre404() throws Exception {
        String missing = java.util.UUID.randomUUID().toString();
        mvc.perform(get("/api/products/" + missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/categories/" + missing)).andExpect(status().isNotFound());
    }

    // ---------- pagination, sorting, filtering ----------

    @Test
    void paginatesWithStableOrderAndCorrectTotals() throws Exception {
        String cat = createCategory("Bulk");
        for (int i = 1; i <= 25; i++) {
            createProduct("sku-%02d".formatted(i), "Item %02d".formatted(i), "1.00", i, cat).andExpect(status().isCreated());
        }

        String page0 = mvc.perform(get("/api/products").param("size", "10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].name").value("Item 01"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/products").param("size", "10").param("page", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[4].name").value("Item 25"));
        mvc.perform(get("/api/products").param("size", "10").param("page", "9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        // Walking all pages yields every product exactly once.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int p = 0; p < 3; p++) {
            String body = mvc.perform(get("/api/products").param("size", "10").param("page", String.valueOf(p)))
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.items[*].id");
            seen.addAll(ids);
        }
        assertThat(seen).hasSize(25);
        assertThat(page0).doesNotContain("passwordHash");
    }

    @Test
    void sortsByWhitelistedFieldsAndRejectsOthers() throws Exception {
        String cat = createCategory("Mixed");
        createProduct("a", "Alpha", "5.00", 1, cat).andExpect(status().isCreated());
        createProduct("b", "Bravo", "50.00", 1, cat).andExpect(status().isCreated());
        createProduct("c", "Charlie", "20.00", 1, cat).andExpect(status().isCreated());

        mvc.perform(get("/api/products").param("sort", "price,desc"))
                .andExpect(jsonPath("$.items[0].name").value("Bravo"))
                .andExpect(jsonPath("$.items[2].name").value("Alpha"));
        mvc.perform(get("/api/products").param("sort", "name,desc"))
                .andExpect(jsonPath("$.items[0].name").value("Charlie"));
        mvc.perform(get("/api/products").param("sort", "category")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("sort", "price;drop table")).andExpect(status().isBadRequest());
    }

    @Test
    void pagingBoundsAreEnforced() throws Exception {
        mvc.perform(get("/api/products").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("size", "100")).andExpect(status().isOk());
        mvc.perform(get("/api/categories").param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void filtersByCategoryAndSearchesNameCaseInsensitivelyWithEscaping() throws Exception {
        String books = createCategory("Books");
        String toys = createCategory("Toys");
        createProduct("b1", "Clean Code", "10", 1, books).andExpect(status().isCreated());
        createProduct("b2", "Refactoring", "10", 1, books).andExpect(status().isCreated());
        createProduct("t1", "Toy Car", "10", 1, toys).andExpect(status().isCreated());
        createProduct("t2", "100% Cotton Bear", "10", 1, toys).andExpect(status().isCreated());

        mvc.perform(get("/api/products").param("categoryId", books))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/products").param("q", "CLEAN"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("B1"));
        mvc.perform(get("/api/products").param("q", "o").param("categoryId", toys))
                .andExpect(jsonPath("$.totalElements").value(2));
        // '%' and '_' are literals, not wildcards
        mvc.perform(get("/api/products").param("q", "%")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/products").param("q", "_")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/products").param("q", "nothing-matches")).andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------- admin writes ----------

    @Test
    void adminCreateReturns201WithLocationAndUppercasedSku() throws Exception {
        String cat = createCategory("Books");

        // surrounding whitespace in a SKU is a client error, not silently trimmed
        createProduct("  cc-1 ", "Clean Code", "29.9", 3, cat).andExpect(status().isBadRequest());
        createProduct("cc-1", "Clean Code", "29.9", 3, cat)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.sku").value("CC-1"))
                .andExpect(jsonPath("$.price").value(29.90));
        assertThat(products.count()).isEqualTo(1);
    }

    @Test
    void adminUpdateChangesStockAndMovesProductToAnotherCategory() throws Exception {
        String books = createCategory("Books");
        String toys = createCategory("Toys");
        String id = createProductId("x1", "Thing", "10.00", 5, books);

        mvc.perform(put("/api/products/" + id).header("Authorization", admin()).contentType(JSON)
                        .content(productJson("x1", "Thing v2", "12.50", 0, toys, versionOf(id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Thing v2"))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.categoryName").value("Toys"));
        var saved = products.findById(java.util.UUID.fromString(id)).orElseThrow();
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
    }

    @Test
    void adminDeleteRemovesProduct() throws Exception {
        String id = createProductId("x1", "Thing", "10.00", 5, createCategory("Books"));

        mvc.perform(delete("/api/products/" + id).header("Authorization", admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/" + id).header("Authorization", admin())).andExpect(status().isNotFound());
    }

    @Test
    void updatingUnknownProductIs404() throws Exception {
        String cat = createCategory("Books");
        mvc.perform(put("/api/products/" + java.util.UUID.randomUUID()).header("Authorization", admin()).contentType(JSON)
                        .content(productJson("x", "n", "1", 1, cat, 0)))
                .andExpect(status().isNotFound());
    }

    // ---------- validation and constraints ----------

    @Test
    void duplicateSkuIs409EvenWithDifferentCaseAndOnUpdate() throws Exception {
        String cat = createCategory("Books");
        createProductId("dup-1", "One", "1", 1, cat);
        String other = createProductId("dup-2", "Two", "1", 1, cat);

        createProduct("DUP-1", "Again", "1", 1, cat).andExpect(status().isConflict());
        mvc.perform(put("/api/products/" + other).header("Authorization", admin()).contentType(JSON)
                        .content(productJson("dup-1", "Two", "1", 1, cat, versionOf(other))))
                .andExpect(status().isConflict());
        // keeping its own SKU on update is fine
        mvc.perform(put("/api/products/" + other).header("Authorization", admin()).contentType(JSON)
                        .content(productJson("dup-2", "Two renamed", "1", 1, cat, versionOf(other))))
                .andExpect(status().isOk());
        assertThat(products.count()).isEqualTo(2);
    }

    @Test
    void invalidProductBodiesAre400AndPersistNothing() throws Exception {
        String cat = createCategory("Books");
        createProduct("a", "n", "-1", 1, cat).andExpect(status().isBadRequest());
        createProduct("a", "n", "1.999", 1, cat).andExpect(status().isBadRequest());
        createProduct("a", "n", "1", -1, cat).andExpect(status().isBadRequest());
        createProduct("bad sku", "n", "1", 1, cat).andExpect(status().isBadRequest());
        createProduct("a", "", "1", 1, cat).andExpect(status().isBadRequest());
        createProduct("a", "n", "1", 1, java.util.UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        assertThat(products.count()).isZero();
    }

    @Test
    void duplicateCategoryNameIs409IgnoringCase() throws Exception {
        createCategory("Books");

        mvc.perform(post("/api/categories").header("Authorization", admin()).contentType(JSON).content("{\"name\":\"bOOks\"}"))
                .andExpect(status().isConflict());
        assertThat(categories.count()).isEqualTo(1);
    }

    @Test
    void categoryWithProductsCannotBeDeletedUntilEmpty() throws Exception {
        String cat = createCategory("Books");
        String product = createProductId("x1", "Thing", "10", 1, cat);

        mvc.perform(delete("/api/categories/" + cat).header("Authorization", admin())).andExpect(status().isConflict());
        mvc.perform(delete("/api/products/" + product).header("Authorization", admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/categories/" + cat).header("Authorization", admin())).andExpect(status().isNoContent());
        assertThat(categories.count()).isZero();
    }

    @Test
    void adminCanUpdateCategory() throws Exception {
        String cat = createCategory("Books");

        mvc.perform(put("/api/categories/" + cat).header("Authorization", admin()).contentType(JSON)
                        .content("{\"name\":\"Novels\",\"description\":\"Fiction\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Novels"));
    }

    // ---------- authentication and authorisation ----------

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b, String authorization) {
        return authorization == null ? b : b.header("Authorization", authorization);
    }

    @Test
    void writeMatrixAnonymous401_Customer403() throws Exception {
        String cat = createCategory("Books");
        String id = createProductId("x1", "Thing", "10", 1, cat);
        String body = productJson("x2", "n", "1", 1, cat);

        for (String auth : new String[] {null, "Bearer not.a.jwt"}) {
            mvc.perform(withAuth(post("/api/products"), auth).contentType(JSON).content(body)).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(put("/api/products/" + id), auth).contentType(JSON).content(body)).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(delete("/api/products/" + id), auth)).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(post("/api/categories"), auth).contentType(JSON).content("{\"name\":\"X\"}")).andExpect(status().isUnauthorized());
            mvc.perform(withAuth(delete("/api/categories/" + cat), auth)).andExpect(status().isUnauthorized());
        }
        String customer = customer();
        mvc.perform(withAuth(post("/api/products"), customer).contentType(JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(withAuth(put("/api/products/" + id), customer).contentType(JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(withAuth(delete("/api/products/" + id), customer)).andExpect(status().isForbidden());
        mvc.perform(withAuth(post("/api/categories"), customer).contentType(JSON).content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
        mvc.perform(withAuth(put("/api/categories/" + cat), customer).contentType(JSON).content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
        mvc.perform(withAuth(delete("/api/categories/" + cat), customer)).andExpect(status().isForbidden());

        // nothing changed
        assertThat(products.count()).isEqualTo(1);
        assertThat(categories.count()).isEqualTo(1);
    }

    @Test
    void tokenWithoutRolesCannotWrite() throws Exception {
        String noRole = "Bearer " + JWKS.token(null);
        mvc.perform(post("/api/categories").header("Authorization", noRole).contentType(JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedExpiredAndWrongIssuerTokensAreRejected() throws Exception {
        var attacker = TestJwks.generate("trusted-key"); // same kid as the real key, different key material
        String forged = "Bearer " + JWKS.sign(attacker, TestJwks.ISSUER, "ADMIN", Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        String expired = "Bearer " + JWKS.sign(TestJwks.generate("trusted-key"), TestJwks.ISSUER, "ADMIN",
                Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1)));
        String wrongIssuer = "Bearer " + JWKS.sign(attacker, "evil-issuer", "ADMIN", Instant.now(), Instant.now().plus(Duration.ofHours(1)));

        for (String bad : new String[] {forged, expired, wrongIssuer}) {
            mvc.perform(post("/api/categories").header("Authorization", bad).contentType(JSON).content("{\"name\":\"Hacked\"}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(categories.count()).isZero();
    }

    @Test
    void healthEndpointIsPublicAndOtherActuatorIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
