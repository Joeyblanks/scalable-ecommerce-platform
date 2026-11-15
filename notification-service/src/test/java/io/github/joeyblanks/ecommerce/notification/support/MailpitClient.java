package io.github.joeyblanks.ecommerce.notification.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads what really arrived at the mail catcher (Mailpit's HTTP API): the ground truth for "was it sent?". */
public final class MailpitClient {

    public record Mail(String id, String messageId, String to, String subject, String text, String html) {
    }

    private final String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    public MailpitClient(String host, int httpPort) {
        this.base = "http://" + host + ":" + httpPort;
    }

    public List<Mail> all() {
        try {
            JsonNode list = get("/api/v1/messages?limit=500");
            List<Mail> mails = new ArrayList<>();
            for (JsonNode m : list.path("messages")) {
                JsonNode detail = get("/api/v1/message/" + m.path("ID").asString());
                mails.add(new Mail(m.path("ID").asString(), detail.path("MessageID").asString(),
                        detail.path("To").path(0).path("Address").asString(), detail.path("Subject").asString(),
                        detail.path("Text").asString(), detail.path("HTML").asString()));
            }
            return mails;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Mail> to(String address) {
        return all().stream().filter(m -> m.to().equalsIgnoreCase(address)).toList();
    }

    public void clear() {
        try {
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(r.body());
    }
}
