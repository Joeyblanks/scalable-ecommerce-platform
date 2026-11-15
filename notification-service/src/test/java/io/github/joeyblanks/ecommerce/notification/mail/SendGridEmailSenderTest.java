package io.github.joeyblanks.ecommerce.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the request we send and how every answer is treated, against a local stub of SendGrid's Mail Send API. */
class SendGridEmailSenderTest {

    private static final String KEY = SendGridKeysTest.KEY;
    private static final Email EMAIL = new Email("orders@shop.local", "ann@example.com", "We received your order #AB12",
            "Hello Ann", "<p>Hello Ann</p>", "<11111111-2222-3333-4444-555555555555@notification-service>");

    record Req(String method, String path, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 202;
    private volatile String responseBody = "";
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            requests.add(new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                ex.getResponseBody().write(out);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SendGridEmailSender sender(boolean sandbox) {
        return new SendGridEmailSender(RestClient.builder(),
                new AppProperties.SendGrid(KEY, "http://127.0.0.1:" + server.getAddress().getPort(), sandbox));
    }

    @Test
    void sendsOneAuthenticatedJsonRequestWithBothPartsAndOurId() throws Exception {
        sender(false).send(EMAIL);

        assertThat(requests).hasSize(1);
        Req r = requests.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v3/mail/send");
        assertThat(r.headers().get("authorization")).isEqualTo("Bearer " + KEY);
        assertThat(r.headers().get("content-type")).startsWith("application/json");
        JsonNode b = json.readTree(r.body());
        assertThat(b.path("personalizations").path(0).path("to").path(0).path("email").asString()).isEqualTo("ann@example.com");
        assertThat(b.path("personalizations").size()).isEqualTo(1);
        assertThat(b.path("from").path("email").asString()).isEqualTo("orders@shop.local");
        assertThat(b.path("subject").asString()).isEqualTo("We received your order #AB12");
        assertThat(b.path("content").path(0).path("type").asString()).isEqualTo("text/plain");
        assertThat(b.path("content").path(0).path("value").asString()).isEqualTo("Hello Ann");
        assertThat(b.path("content").path(1).path("type").asString()).isEqualTo("text/html");
        assertThat(b.path("content").path(1).path("value").asString()).isEqualTo("<p>Hello Ann</p>");
        assertThat(b.path("headers").path("X-Notification-Id").asString()).isEqualTo(EMAIL.messageId());
        assertThat(b.path("custom_args").path("notification_id").asString()).isEqualTo(EMAIL.messageId());
        assertThat(b.has("mail_settings")).as("really delivers unless sandbox is asked for").isFalse();
    }

    @Test
    void sandboxModeIsPassedOnSoNothingIsDelivered() throws Exception {
        sender(true).send(EMAIL);

        JsonNode b = json.readTree(requests.get(0).body());
        assertThat(b.path("mail_settings").path("sandbox_mode").path("enable").asBoolean()).isTrue();
    }

    @Test
    void everyAnswerOtherThanAcceptedIsAFailureThatIsRetriedLaterNeverSilentlyDropped() {
        // 200 is not what SendGrid answers on success (202); a 4xx/5xx always means "not sent (as far as we know)"
        for (int s : new int[]{400, 401, 403, 413, 429, 500, 502, 503}) {
            status = s;
            responseBody = "{\"errors\":[{\"message\":\"The to email address ann@example.com is invalid\",\"field\":\"personalizations.0.to.0.email\"}]}";
            assertThatThrownBy(() -> sender(false).send(EMAIL)).as("http %s", s).isInstanceOf(EmailSendException.class)
                    .hasMessageContaining(String.valueOf(s));
        }
    }

    @Test
    void onlyAcceptedCountsAsSentNotARedirectOrAnEmptyAnswer() {
        for (int s : new int[]{201, 204, 301, 302, 307}) {
            status = s;
            assertThatThrownBy(() -> sender(false).send(EMAIL)).as("http %s", s).isInstanceOf(EmailSendException.class)
                    .hasMessageContaining(String.valueOf(s));
        }
        status = 202;
        sender(false).send(EMAIL);
        status = 200; // sandbox mode
        sender(true).send(EMAIL);
    }

    @Test
    void errorMessagesNeverContainTheAddressTheBodyOrTheKey() {
        status = 400;
        responseBody = "{\"errors\":[{\"message\":\"The to email address ann@example.com is invalid\"}]}";

        assertThatThrownBy(() -> sender(false).send(EMAIL)).isInstanceOf(EmailSendException.class)
                .message().doesNotContain("ann@example.com").doesNotContain("invalid").doesNotContain(KEY).doesNotContain("SG.");
    }

    @Test
    void anUnreachableSendGridIsAFailure() {
        SendGridEmailSender nobody = new SendGridEmailSender(RestClient.builder(), new AppProperties.SendGrid(KEY, "http://127.0.0.1:1", false));

        assertThatThrownBy(() -> nobody.send(EMAIL)).isInstanceOf(EmailSendException.class).message().doesNotContain(KEY);
    }

    @Test
    void thereAreNoHiddenRetries() {
        status = 500;

        assertThatThrownBy(() -> sender(false).send(EMAIL)).isInstanceOf(EmailSendException.class);

        assertThat(requests).as("retrying is the worker's job, under its lease and backoff").hasSize(1);
    }

    @Test
    void theSettingsNeverPrintTheKey() {
        assertThat(new AppProperties.SendGrid(KEY, "http://x", false).toString()).doesNotContain("SG.").doesNotContain(KEY);
    }

    @Test
    void aBadKeyStopsConstruction() {
        assertThatThrownBy(() -> new SendGridEmailSender(RestClient.builder(), new AppProperties.SendGrid("sk_live_abcdefghijklmnop", "http://x", false)))
                .isInstanceOf(IllegalStateException.class).message().doesNotContain("sk_live");
    }
}
