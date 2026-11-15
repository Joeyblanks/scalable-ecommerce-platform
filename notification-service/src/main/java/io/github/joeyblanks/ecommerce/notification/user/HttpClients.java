package io.github.joeyblanks.ecommerce.notification.user;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** A RestClient with explicit, short timeouts: a slow dependency must fail fast, never hang a worker. */
final class HttpClients {

    private HttpClients() {
    }

    static RestClient create(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
