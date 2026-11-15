package io.github.joeyblanks.ecommerce.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Maps Stripe's answers to our results, against a local stub that speaks Stripe's wire format. (The real
 * request/response schema is checked separately against stripe-mock in StripeMockIT.)
 */
class StripePaymentProviderTest {

    private static final String KEY = "sk_test_51Abcdefghijkl";

    record Req(String method, String path, Map<String, String> headers, String body) {
    }

    record Resp(int status, String body) {
    }

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Req, Resp> handler = r -> new Resp(500, "{}");
    private StripePaymentProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body);
            requests.add(req);
            Resp resp = handler.apply(req);
            byte[] out = resp.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(resp.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        provider = providerFor("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static StripePaymentProvider providerFor(String base) {
        return new StripePaymentProvider(new AppProperties.Stripe(KEY, base));
    }

    private static String intent(String status) {
        return "{\"id\":\"pi_123\",\"object\":\"payment_intent\",\"status\":\"" + status + "\",\"amount\":1999,\"currency\":\"eur\"}";
    }

    private static String error(String type, String code, String declineCode) {
        return "{\"error\":{\"type\":\"" + type + "\",\"code\":\"" + code + "\""
                + (declineCode == null ? "" : ",\"decline_code\":\"" + declineCode + "\"")
                + ",\"message\":\"card number 4242424242424242 problem\"}}";
    }

    private Map<String, String> form(Req r) {
        Map<String, String> m = new java.util.HashMap<>();
        for (String pair : r.body().split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void aSucceededPaymentIntentIsASuccessAndKeepsTheIntentIdForRefunds() {
        handler = r -> new Resp(200, intent("succeeded"));

        ChargeResult result = provider.charge(new BigDecimal("19.99"), "EUR", "charge-abc");

        assertThat(result).isEqualTo(new ChargeResult.Succeeded("pi_123"));
    }

    @Test
    void theRequestCarriesTheIdempotencyKeyTheTestKeyMinorUnitsAndTheTestPaymentMethod() {
        handler = r -> new Resp(200, intent("succeeded"));

        provider.charge(new BigDecimal("19.99"), "EUR", "charge-abc");

        Req r = requests.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v1/payment_intents");
        assertThat(r.headers().get("idempotency-key")).isEqualTo("charge-abc");
        assertThat(r.headers().get("authorization")).isEqualTo("Bearer " + KEY);
        Map<String, String> f = form(r);
        assertThat(f).containsEntry("amount", "1999").containsEntry("currency", "eur")
                .containsEntry("confirm", "true").containsEntry("payment_method", "pm_card_visa")
                .containsEntry("automatic_payment_methods[enabled]", "true")
                .containsEntry("automatic_payment_methods[allow_redirects]", "never");
    }

    @Test
    void minorUnitsAreExact() {
        assertThat(StripePaymentProvider.minorUnits(new BigDecimal("19.99"))).isEqualTo(1999);
        assertThat(StripePaymentProvider.minorUnits(new BigDecimal("0.10"))).isEqualTo(10);
        assertThat(StripePaymentProvider.minorUnits(new BigDecimal("1234567.89"))).isEqualTo(123456789L);
        assertThat(StripePaymentProvider.minorUnits(new BigDecimal("20"))).isEqualTo(2000);
    }

    @Test
    void theCentsPickTheSameTestPaymentMethodsAsTheSimulatedRules() {
        assertThat(StripePaymentProvider.testPaymentMethodFor(new BigDecimal("20.13"))).isEqualTo("pm_card_chargeDeclined");
        assertThat(StripePaymentProvider.testPaymentMethodFor(new BigDecimal("20.14"))).isEqualTo("pm_card_chargeDeclinedInsufficientFunds");
        assertThat(StripePaymentProvider.testPaymentMethodFor(new BigDecimal("20.15"))).isEqualTo("pm_card_visa");
        assertThat(StripePaymentProvider.testPaymentMethodFor(new BigDecimal("19.99"))).isEqualTo("pm_card_visa");
    }

    @Test
    void aCardDeclineIsDefinitiveAndUsesOurCodeNotStripesText() {
        handler = r -> new Resp(402, error("card_error", "card_declined", "generic_decline"));

        ChargeResult result = provider.charge(new BigDecimal("20.13"), "EUR", "k");

        assertThat(result).isEqualTo(new ChargeResult.Declined("CARD_DECLINED"));
    }

    @Test
    void insufficientFundsHasItsOwnCode() {
        handler = r -> new Resp(402, error("card_error", "card_declined", "insufficient_funds"));

        assertThat(provider.charge(new BigDecimal("20.14"), "EUR", "k")).isEqualTo(new ChargeResult.Declined("INSUFFICIENT_FUNDS"));
    }

    @Test
    void everythingElseIsUnknownNeverADecline() {
        for (Resp r : List.of(
                new Resp(500, "{\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}"),
                new Resp(503, "oops"),
                new Resp(429, error("invalid_request_error", "rate_limit", null)),
                new Resp(401, error("invalid_request_error", "api_key_expired", null)),
                new Resp(403, error("invalid_request_error", "permission", null)),
                new Resp(400, error("invalid_request_error", "parameter_invalid_integer", null)),
                new Resp(409, error("idempotency_error", "idempotency_key_in_use", null)),
                new Resp(200, "not json at all"))) {
            handler = req -> r;
            ChargeResult result = provider.charge(new BigDecimal("19.99"), "EUR", "k");
            assertThat(result).as("http %s", r.status()).isInstanceOf(ChargeResult.Transient.class);
        }
    }

    @Test
    void aPaymentIntentThatIsNotSucceededIsNotASuccessAndNotADecline() {
        for (String status : List.of("processing", "requires_action", "requires_payment_method", "requires_capture")) {
            handler = r -> new Resp(200, intent(status));
            assertThat(provider.charge(new BigDecimal("19.99"), "EUR", "k")).as(status).isInstanceOf(ChargeResult.Transient.class);
        }
    }

    @Test
    void anUnreachableStripeIsUnknown() {
        StripePaymentProvider nobody = providerFor("http://127.0.0.1:1");

        assertThat(nobody.charge(new BigDecimal("19.99"), "EUR", "k")).isInstanceOf(ChargeResult.Transient.class);
        assertThat(nobody.refund("pi_1", "k")).isInstanceOf(RefundResult.Transient.class);
    }

    @Test
    void theSdkDoesNotRetryOnItsOwn() {
        handler = r -> new Resp(500, "{}");

        provider.charge(new BigDecimal("19.99"), "EUR", "k");

        assertThat(requests).hasSize(1); // retrying (with the same key) is the worker's job
    }

    @Test
    void errorTextIsNeverCopiedIntoTheResult() {
        handler = r -> new Resp(400, error("invalid_request_error", "x", null));

        ChargeResult.Transient t = (ChargeResult.Transient) provider.charge(new BigDecimal("19.99"), "EUR", "k");

        assertThat(t.error()).doesNotContain("4242");
    }

    @Test
    void aRefundTargetsThePaymentIntentWithItsOwnIdempotencyKey() {
        handler = r -> new Resp(200, "{\"id\":\"re_9\",\"object\":\"refund\",\"status\":\"succeeded\"}");

        RefundResult result = provider.refund("pi_123", "refund-abc");

        assertThat(result).isEqualTo(new RefundResult.Refunded("re_9"));
        Req r = requests.get(0);
        assertThat(r.path()).isEqualTo("/v1/refunds");
        assertThat(r.headers().get("idempotency-key")).isEqualTo("refund-abc");
        assertThat(form(r)).containsEntry("payment_intent", "pi_123");
    }

    @Test
    void aPendingRefundCountsAsRefundedButAFailedOneIsRetried() {
        handler = r -> new Resp(200, "{\"id\":\"re_9\",\"object\":\"refund\",\"status\":\"pending\"}");
        assertThat(provider.refund("pi_1", "k")).isEqualTo(new RefundResult.Refunded("re_9"));

        handler = r -> new Resp(200, "{\"id\":\"re_9\",\"object\":\"refund\",\"status\":\"failed\"}");
        assertThat(provider.refund("pi_1", "k")).isInstanceOf(RefundResult.Transient.class);
    }

    @Test
    void refundErrorsAreUnknown() {
        for (int status : new int[] {500, 429, 400, 401}) {
            handler = r -> new Resp(status, error("invalid_request_error", "x", null));
            assertThat(provider.refund("pi_1", "k")).as("http %s", status).isInstanceOf(RefundResult.Transient.class);
        }
    }
}
