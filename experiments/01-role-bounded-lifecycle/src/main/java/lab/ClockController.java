package lab;

import java.net.URI;
import java.net.http.*;
import java.time.*;

/** Isolated management API assumptions; never expose this endpoint publicly. */
public final class ClockController implements AutoCloseable {
    static final String PIN = "/actuator/clock/pin"; // POST (verified live on 8.10.7)
    static final String ADD = "/actuator/clock/add";
    static final String RESET = "/actuator/clock"; // DELETE resets (verified live on 8.10.7)
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base = Main.env("ZEEBE_MANAGEMENT_ADDRESS", "http://localhost:9600").replaceAll("/+$", "");
    private Instant pinned;
    public void pin(Instant instant) throws Exception {
        request("POST", PIN, "{\"epochMilli\":" + instant.toEpochMilli() + "}");
        pinned = instant;
    }
    public void advanceTo(Instant instant) throws Exception {
        if (pinned == null || instant.isBefore(pinned)) throw new IllegalArgumentException("Clock must advance monotonically after pin");
        request("POST", ADD, "{\"offsetMilli\":" + Duration.between(pinned, instant).toMillis() + "}");
        // Finding (8.10.7, controlled clock): after one big jump the timer due-date checker does not fire a timer start
        // that became due; a second small step after a short pause makes it re-evaluate. Reproduced both ways.
        Thread.sleep(3000);
        request("POST", ADD, "{\"offsetMilli\":1000}");
        pinned = instant.plusSeconds(1);
    }
    public void reset() throws Exception { request("DELETE", RESET, ""); }
    private void request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
        new AccessCredentials(new io.camunda.client.impl.NoopCredentialsProvider(), System.getenv())
            .applyCredentials(request::header);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Clock " + method + " " + path + " HTTP " + response.statusCode());
    }
    public void close() { http.close(); }
}
