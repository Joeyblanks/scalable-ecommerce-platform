package io.github.joeyblanks.ecommerce.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;

/** The service exposes health and nothing else, and a mail outage does not make it unhealthy. */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"spring.mail.port=1"}) // nothing listens there: SMTP is "down"
class StartupIT extends AbstractNotificationIT {

    @Value("${local.server.port}") int port;

    @Test
    void healthIsUpEvenWhenTheMailServerIsDown() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("UP");
    }

    @Test
    void thereIsNoOtherEndpoint() throws Exception {
        for (String path : new String[]{"/api/notifications", "/actuator/env", "/actuator/beans"}) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(404);
        }
    }
}
