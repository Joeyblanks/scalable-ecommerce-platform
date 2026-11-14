package io.github.joeyblanks.ecommerce.cart.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for product-catalog-service: serves GET /api/products/{id} over real HTTP and can be
 * made slow, broken or garbled to exercise the cart's failure handling. Shaped like the real catalog
 * response (including fields the cart does not use).
 */
public final class CatalogStub implements AutoCloseable {

    public enum Mode { OK, ERROR_500, GARBAGE, EMPTY_BODY }

    public record Product(UUID id, String sku, String name, BigDecimal price, int stock) {
    }

    private final Map<UUID, Product> products = new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Mode mode = Mode.OK;
    private volatile Duration delay = Duration.ZERO;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public CatalogStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/api/products/", this::handle);
            server.setExecutor(executor); // a delayed request must not block the others
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public Product put(String sku, String name, String price, int stock) {
        Product p = new Product(UUID.randomUUID(), sku, name, new BigDecimal(price), stock);
        products.put(p.id(), p);
        return p;
    }

    public void update(Product p) {
        products.put(p.id(), p);
    }

    public int hits() { return hits.get(); }

    public void reset() {
        products.clear();
        hits.set(0);
        mode = Mode.OK;
        delay = Duration.ZERO;
    }

    public void mode(Mode mode) { this.mode = mode; }

    public void delay(Duration delay) { this.delay = delay; }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String path = exchange.getRequestURI().getPath();
        String idText = path.substring(path.lastIndexOf('/') + 1);
        int status = 200;
        String body;
        switch (mode) {
            case ERROR_500 -> { status = 500; body = "{\"status\":500}"; }
            case GARBAGE -> body = "this is <not> json";
            case EMPTY_BODY -> body = "";
            default -> {
                Product p = lookup(idText);
                if (p == null) {
                    status = 404;
                    body = "{\"status\":404,\"title\":\"Not Found\"}";
                } else {
                    body = "{\"id\":\"" + p.id() + "\",\"sku\":\"" + p.sku() + "\",\"name\":\"" + p.name()
                            + "\",\"description\":null,\"price\":" + p.price().toPlainString()
                            + ",\"stockQuantity\":" + p.stock() + ",\"categoryId\":\"" + UUID.randomUUID()
                            + "\",\"categoryName\":\"Books\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                            + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}";
                }
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private Product lookup(String idText) {
        try {
            return products.get(UUID.fromString(idText));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
