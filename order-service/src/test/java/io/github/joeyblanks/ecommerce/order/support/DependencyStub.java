package io.github.joeyblanks.ecommerce.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the three services order-service calls, over real HTTP, with their real semantics:
 * <ul>
 *   <li>user-service {@code POST /oauth2/token} (client credentials, checks the secret and scope)</li>
 *   <li>cart-service {@code GET /api/cart} (one cart per user, chosen by the token's subject)</li>
 *   <li>catalog {@code /internal/stock/reservations}: all-or-nothing, idempotent by id, tombstones on
 *       unknown release (same rules as product-catalog-service)</li>
 * </ul>
 * Each can be made to fail: down, slow, or "reserved but answered too late".
 */
public final class DependencyStub implements AutoCloseable {

    public static final String CLIENT_ID = "order-service";
    public static final String CLIENT_SECRET = "test-client-secret";

    public enum CatalogMode { OK, ERROR_500, RESERVE_THEN_STALL }

    public record Product(UUID id, String sku, String name, BigDecimal price, int stock) {
    }

    private record Reservation(String status, Map<UUID, Integer> items) {
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<UUID, Product> products = new ConcurrentHashMap<>();
    private final Map<UUID, Reservation> reservations = new ConcurrentHashMap<>();
    private final Map<UUID, List<Map<String, Object>>> carts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> releaseCalls = new ConcurrentHashMap<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger reserveCalls = new AtomicInteger();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final List<String> issuedTokens = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> cartAuthorizations = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile CatalogMode catalogMode = CatalogMode.OK;
    private volatile Duration stall = Duration.ZERO;
    private volatile boolean cartDown;
    private volatile int releaseFailuresLeft;

    public DependencyStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/oauth2/token", this::token);
            server.createContext("/api/cart", this::cart);
            server.createContext("/internal/stock/reservations", this::reservations);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    // ---------- test controls ----------

    public synchronized void reset() {
        products.clear();
        reservations.clear();
        carts.clear();
        releaseCalls.clear();
        tokenRequests.set(0);
        reserveCalls.set(0);
        cartAuthorizations.clear();
        catalogMode = CatalogMode.OK;
        stall = Duration.ZERO;
        cartDown = false;
        releaseFailuresLeft = 0;
    }

    public Product product(String sku, String price, int stock) {
        Product p = new Product(UUID.randomUUID(), sku, sku + " name", new BigDecimal(price), stock);
        products.put(p.id(), p);
        return p;
    }

    public synchronized void setStock(UUID productId, int stock) {
        Product p = products.get(productId);
        products.put(productId, new Product(p.id(), p.sku(), p.name(), p.price(), stock));
    }

    public synchronized void setPrice(UUID productId, String price) {
        Product p = products.get(productId);
        products.put(productId, new Product(p.id(), p.sku(), p.name(), new BigDecimal(price), p.stock()));
    }

    public int stock(UUID productId) {
        return products.get(productId).stock();
    }

    /** Puts a line in the user's cart; {@code shownPrice} is the cart's (possibly stale) snapshot. */
    public void cartLine(UUID userId, Product p, int quantity, String shownPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", p.id().toString());
        line.put("sku", p.sku());
        line.put("name", p.name());
        line.put("unitPrice", new BigDecimal(shownPrice));
        line.put("quantity", quantity);
        line.put("lineTotal", new BigDecimal(shownPrice).multiply(BigDecimal.valueOf(quantity)));
        carts.computeIfAbsent(userId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(line);
    }

    public void catalogMode(CatalogMode mode, Duration stall) {
        this.catalogMode = mode;
        this.stall = stall;
    }

    public void cartDown(boolean down) { this.cartDown = down; }

    public void failNextReleases(int count) { this.releaseFailuresLeft = count; }

    public int tokenRequests() { return tokenRequests.get(); }

    public int reserveCalls() { return reserveCalls.get(); }

    public int releaseCalls(UUID reservationId) {
        AtomicInteger n = releaseCalls.get(reservationId);
        return n == null ? 0 : n.get();
    }

    public String reservationStatus(UUID reservationId) {
        Reservation r = reservations.get(reservationId);
        return r == null ? null : r.status();
    }

    public List<String> cartAuthorizations() { return cartAuthorizations; }

    // ---------- handlers ----------

    private void token(HttpExchange ex) throws IOException {
        tokenRequests.incrementAndGet();
        String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if (!form.contains("grant_type=client_credentials") || !form.contains("scope=stock%3Areserve")) {
            send(ex, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        String token = "svc-token-" + tokenCounter.incrementAndGet();
        issuedTokens.add(token);
        send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"stock:reserve\"}");
    }

    private void cart(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        cartAuthorizations.add(auth);
        if (cartDown) {
            send(ex, 500, "{}");
            return;
        }
        UUID user = subjectOf(auth);
        if (user == null) {
            send(ex, 401, "{}");
            return;
        }
        List<Map<String, Object>> items = carts.getOrDefault(user, List.of());
        send(ex, 200, json.writeValueAsString(Map.of("items", items, "itemCount", items.size())));
    }

    private void reservations(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !issuedTokens.contains(auth.replace("Bearer ", ""))) {
            send(ex, 401, "{}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/release")) {
            release(ex, UUID.fromString(path.split("/")[4]));
        } else {
            reserve(ex);
        }
    }

    private void reserve(HttpExchange ex) throws IOException {
        reserveCalls.incrementAndGet();
        if (catalogMode == CatalogMode.ERROR_500) {
            send(ex, 500, "{}");
            return;
        }
        JsonNode body = json.readTree(ex.getRequestBody());
        UUID id = UUID.fromString(body.get("reservationId").asString());
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        body.get("items").forEach(i -> wanted.put(UUID.fromString(i.get("productId").asString()), i.get("quantity").asInt()));
        int status;
        String response;
        synchronized (this) {
            Reservation existing = reservations.get(id);
            if (existing != null && "RELEASED".equals(existing.status())) {
                status = 409;
                response = "{\"detail\":\"already released\"}";
            } else if (existing != null) {
                status = 200;
                response = reservationJson(id, existing.items());
            } else {
                List<Map<String, Object>> shortages = new ArrayList<>();
                wanted.forEach((pid, q) -> {
                    Product p = products.get(pid);
                    if (p == null || p.stock() < q) {
                        shortages.add(Map.of("productId", pid.toString(), "requested", q,
                                "available", p == null ? 0 : p.stock(), "reason", p == null ? "PRODUCT_NOT_FOUND" : "INSUFFICIENT_STOCK"));
                    }
                });
                if (!shortages.isEmpty()) {
                    status = 409;
                    response = json.writeValueAsString(Map.of("status", 409, "shortages", shortages));
                } else {
                    wanted.forEach((pid, q) -> {
                        Product p = products.get(pid);
                        products.put(pid, new Product(p.id(), p.sku(), p.name(), p.price(), p.stock() - q));
                    });
                    reservations.put(id, new Reservation("RESERVED", wanted));
                    status = 201;
                    response = reservationJson(id, wanted);
                }
            }
        }
        if (catalogMode == CatalogMode.RESERVE_THEN_STALL) {
            sleep(stall); // the reservation HAS happened, but the answer arrives too late
        }
        send(ex, status, response);
    }

    private void release(HttpExchange ex, UUID id) throws IOException {
        releaseCalls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        synchronized (this) {
            if (releaseFailuresLeft > 0) {
                releaseFailuresLeft--;
                send(ex, 500, "{}");
                return;
            }
            Reservation r = reservations.get(id);
            if (r == null) {
                reservations.put(id, new Reservation("RELEASED", Map.of())); // tombstone
            } else if ("RESERVED".equals(r.status())) {
                r.items().forEach((pid, q) -> {
                    Product p = products.get(pid);
                    products.put(pid, new Product(p.id(), p.sku(), p.name(), p.price(), p.stock() + q));
                });
                reservations.put(id, new Reservation("RELEASED", r.items()));
            }
        }
        send(ex, 200, "{\"reservationId\":\"" + id + "\",\"status\":\"RELEASED\"}");
    }

    private String reservationJson(UUID id, Map<UUID, Integer> items) {
        List<Map<String, Object>> lines = new ArrayList<>();
        items.forEach((pid, q) -> {
            Product p = products.get(pid);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productId", pid.toString());
            line.put("sku", p.sku());
            line.put("name", p.name());
            line.put("unitPrice", p.price());
            line.put("quantity", q);
            line.put("lineTotal", p.price().multiply(BigDecimal.valueOf(q)));
            lines.add(line);
        });
        return json.writeValueAsString(Map.of("reservationId", id.toString(), "status", "RESERVED", "items", lines));
    }

    private UUID subjectOf(String authorization) {
        try {
            String payload = authorization.replace("Bearer ", "").split("\\.")[1];
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(payload));
            return UUID.fromString(claims.get("sub").asString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
