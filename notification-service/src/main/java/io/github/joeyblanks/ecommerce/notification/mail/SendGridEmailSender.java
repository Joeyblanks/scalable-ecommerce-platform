package io.github.joeyblanks.ecommerce.notification.mail;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * SendGrid (v3 Mail Send API). Used only when {@code EMAIL_PROVIDER=sendgrid}, or when it is {@code auto}
 * (default) and {@code SENDGRID_API_KEY} is set; the key comes only from the environment.
 *
 * <p>No SDK: one JSON POST, so there is nothing to retry behind our back. Only 202 Accepted (200 in sandbox mode) counts
 * as sent; any other answer, and any network trouble, throws, and the worker retries with backoff under its own lease (the same rule as SMTP).
 * SendGrid, like SMTP, has no idempotency key, so the limit in ADR 010 section 3 is unchanged.
 *
 * <p>Our stable id travels as the {@code X-Notification-Id} header and as a custom argument (visible in SendGrid's
 * activity and webhooks). SendGrid generates the Message-ID itself, so unlike SMTP it cannot be set.
 */
@Component
@ConditionalOnExpression("'${app.email.provider:auto}' == 'sendgrid' or "
        + "('${app.email.provider:auto}' == 'auto' and !'${app.sendgrid.api-key:}'.isBlank())")
public class SendGridEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailSender.class);

    private final RestClient http;
    private final String apiKey;
    private final boolean sandbox;

    public SendGridEmailSender(RestClient.Builder builder, AppProperties.SendGrid config) {
        this.apiKey = SendGridKeys.requireKey(config.apiKey());
        this.sandbox = config.sandbox();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.clone().baseUrl(config.apiBase()).requestFactory(factory).build();
        if (sandbox) {
            log.warn("Using the SENDGRID email sender in SANDBOX mode: SendGrid validates requests but delivers nothing.");
        } else {
            log.warn("Using the SENDGRID email sender: emails are REALLY delivered to the customers' addresses.");
        }
    }

    @Override
    public String name() {
        return "sendgrid";
    }

    @Override
    public void send(Email email) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("personalizations", List.of(Map.of("to", List.of(Map.of("email", email.to())))));
        body.put("from", Map.of("email", email.from()));
        body.put("subject", email.subject());
        // SendGrid requires text/plain before text/html
        body.put("content", List.of(Map.of("type", "text/plain", "value", email.text()),
                Map.of("type", "text/html", "value", email.html())));
        body.put("headers", Map.of("X-Notification-Id", email.messageId()));
        body.put("custom_args", Map.of("notification_id", email.messageId()));
        if (sandbox) {
            body.put("mail_settings", Map.of("sandbox_mode", Map.of("enable", true)));
        }
        try {
            var response = http.post().uri("/v3/mail/send")
                    .headers(h -> h.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            int code = response.getStatusCode().value();
            // 202 Accepted; sandbox mode answers 200. Anything else that is not an error status (a redirect from a
            // wrong base URL, a 204 from a proxy) must not be mistaken for "sent".
            if (code != 202 && code != 200) {
                throw new EmailSendException("SendGrid answered " + code, null);
            }
        } catch (RestClientResponseException e) {
            // Status only: the error body can echo the recipient address.
            throw new EmailSendException("SendGrid answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new EmailSendException("Could not reach SendGrid: " + e.getClass().getSimpleName(), e);
        }
    }
}
