package io.github.joeyblanks.ecommerce.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole path with SendGrid selected: event -> row -> user lookup -> SendGrid request. SendGrid is a stub that
 * speaks the Mail Send API; nothing else (database, broker, user lookup, retries) is faked.
 */
class SendGridFlowIT extends AbstractNotificationIT {

    static final AtomicInteger NEXT_STATUS_COUNTDOWN = new AtomicInteger();
    static final List<String> BODIES = new CopyOnWriteArrayList<>();
    static final List<String> AUTH = new CopyOnWriteArrayList<>();
    static final HttpServer SENDGRID = start();

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/v3/mail/send", ex -> {
                BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AUTH.add(ex.getRequestHeaders().getFirst("Authorization"));
                int status = NEXT_STATUS_COUNTDOWN.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0 ? 503 : 202;
                ex.sendResponseHeaders(status, -1);
                ex.close();
            });
            s.start();
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void sendGrid(DynamicPropertyRegistry r) {
        r.add("app.sendgrid.api-key", () -> io.github.joeyblanks.ecommerce.notification.mail.SendGridKeysTest.KEY);
        r.add("app.sendgrid.api-base", () -> "http://127.0.0.1:" + SENDGRID.getAddress().getPort());
    }

    @BeforeEach
    void resetStub() {
        NEXT_STATUS_COUNTDOWN.set(0);
        BODIES.clear();
        AUTH.clear();
    }

    @Test
    void anOrderEventIsSentThroughSendGridNotSmtp() {
        UUID user = UUID.randomUUID();
        USERS.put(user, "ann@example.com", "Ann Lee");
        UUID event = UUID.randomUUID();
        send("order.paid", paidJson(event, UUID.randomUUID(), user));

        awaitStatus(event, "SENT");

        assertThat(BODIES).hasSize(1);
        assertThat(BODIES.get(0)).contains("ann@example.com").contains("Payment confirmed for order").contains(event.toString());
        assertThat(AUTH.get(0)).startsWith("Bearer SG.");
        assertThat(mailbox.all()).as("nothing went to the SMTP server").isEmpty();
        assertThat(sender.name()).isEqualTo("controlled-sendgrid");
    }

    @Test
    void aSendGridOutageIsRetriedAndTheEmailIsSentOnceItRecovers() {
        UUID user = UUID.randomUUID();
        USERS.put(user, "bob@example.com", "Bob");
        UUID event = UUID.randomUUID();
        NEXT_STATUS_COUNTDOWN.set(2); // two 503 answers, then 202
        send("order.paid", paidJson(event, UUID.randomUUID(), user));

        awaitStatus(event, "SENT");

        assertThat(BODIES).as("two failed attempts and the one that was accepted").hasSize(3);
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications", String.class)).isNull();
    }
}
