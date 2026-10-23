package dev.traction.demo.governed;

import dev.traction.demo.governed.AcmeExceptions.AuthException;
import dev.traction.demo.governed.AcmeExceptions.BadPayloadException;
import dev.traction.demo.governed.AcmeExceptions.ContractException;
import dev.traction.demo.governed.AcmeExceptions.RateLimitedException;
import dev.traction.demo.governed.AcmeExceptions.VendorUnavailableException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * A thin HTTP client for the Acme vendor. Every failure is thrown as one of
 * {@link AcmeExceptions}, chosen by status; it knows nothing about Traction.
 */
@Component
public class AcmeClient {

    public static final String CONTACTS = "/contacts";
    public static final String COMPANIES = "/companies";
    public static final String EMAILS = "/emails";

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** The payloads Acme accepts. {@code bad=true} marks a record Acme's bad_payloads mode rejects. */
    public record Contact(String id, String email, boolean bad) { }

    public record Company(String id, String domain, boolean bad) { }

    public record WelcomeEmail(String id, String to, boolean bad) { }

    private final RestClient http;

    public AcmeClient(@Value("${acme.base-url:http://acme-vendor:9090}") String baseUrl) {
        JdkClientHttpRequestFactory requests =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requests.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    /** Creates or updates one record at {@code path}; idempotent on the record's id. */
    public void upsert(String path, Object record) {
        try {
            http.post().uri(path).body(record).retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        throw failure(status, new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new VendorUnavailableException(0, e.getMessage()); // timeout, connection refused
        }
    }

    /** The exception for an error status (package-private for the reporter test). */
    static RuntimeException failure(int status, String body) {
        return switch (status) {
            case 401 -> new AuthException(status, body);
            case 422 -> new ContractException(status, body);
            case 400 -> new BadPayloadException(status, body);
            case 429 -> new RateLimitedException(status, body);
            default -> status >= 500
                    ? new VendorUnavailableException(status, body)
                    : new IllegalStateException("unexpected Acme response " + status + " " + body);
        };
    }
}
