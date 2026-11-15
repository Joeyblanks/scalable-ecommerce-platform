package io.github.joeyblanks.ecommerce.notification.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays user-service: the token endpoint and the internal contact endpoint, speaking the real wire format and
 * checking what a real user-service checks (client credentials, explicitly requested scope, Bearer token).
 */
public final class FakeUserService implements AutoCloseable {

    public static final String CLIENT_ID = "notification-service";
    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String SCOPE = "users:read-contact";

    public record Person(String email, String fullName) {
    }

    private final HttpServer server;
    private final Map<UUID, Person> users = new ConcurrentHashMap<>();
    public final AtomicInteger tokenRequests = new AtomicInteger();
    public final AtomicInteger contactRequests = new AtomicInteger();
    public final List<String> tokenScopes = new CopyOnWriteArrayList<>();
    private volatile int failWith = 0;           // when > 0 the contact endpoint answers this status
    private volatile int expiresIn = 300;
    private volatile String currentToken = "token-1";
    private final AtomicInteger issued = new AtomicInteger(1);

    public FakeUserService() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/oauth2/token", this::token);
            server.createContext("/api/internal/users/", this::contact);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void put(UUID id, String email, String fullName) {
        users.put(id, new Person(email, fullName));
    }

    public void remove(UUID id) {
        users.remove(id);
    }

    /** Make the contact endpoint answer {@code status} (e.g. 503); 0 = back to normal. */
    public void failWith(int status) {
        this.failWith = status;
    }

    public void tokenLifetimeSeconds(int seconds) {
        this.expiresIn = seconds;
    }

    public void reset() {
        users.clear();
        failWith = 0;
        expiresIn = 300;
        tokenRequests.set(0);
        contactRequests.set(0);
        tokenScopes.clear();
        issued.set(1);
        currentToken = "token-1";
    }

    private void token(HttpExchange ex) throws IOException {
        tokenRequests.incrementAndGet();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!expected.equals(auth)) {
            reply(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if (!body.contains("grant_type=client_credentials")) {
            reply(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        String scope = body.contains("scope=") ? body.replaceAll(".*scope=([^&]*).*", "$1").replace("%3A", ":") : "";
        tokenScopes.add(scope);
        currentToken = "token-" + issued.incrementAndGet();
        reply(ex, 200, "{\"access_token\":\"" + currentToken + "\",\"scope\":\"" + scope + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}");
    }

    private void contact(HttpExchange ex) throws IOException {
        contactRequests.incrementAndGet();
        if (failWith > 0) {
            reply(ex, failWith, "{}");
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer token-")) {
            reply(ex, 401, "{}");
            return;
        }
        String[] parts = ex.getRequestURI().getPath().split("/"); // /api/internal/users/{id}/contact
        UUID id;
        try {
            id = UUID.fromString(parts[4]);
        } catch (RuntimeException e) {
            reply(ex, 400, "{}");
            return;
        }
        Person p = users.get(id);
        if (p == null) {
            reply(ex, 404, "{\"title\":\"Not Found\"}");
            return;
        }
        reply(ex, 200, "{\"userId\":\"" + id + "\",\"email\":\"" + p.email() + "\",\"fullName\":\"" + p.fullName() + "\"}");
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
