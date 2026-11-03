package lab;

import io.camunda.client.CredentialsProvider;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Offline check; no sockets, token requests or credential values printed. */
public final class AccessCredentialsCheck {
    public static void main(String[] args) throws Exception {
        var delegate = new CredentialsProvider() {
            public void applyCredentials(CredentialsApplier a) { a.put("Authorization", "Bearer synthetic"); }
            public boolean shouldRetryRequest(StatusCode c) { return true; }
        };
        var headers = new HashMap<String, String>();
        var wrapped = new AccessCredentials(delegate, Map.of("GATEWAY_CLIENT_ID", "synthetic-id", "GATEWAY_CLIENT_SECRET", "synthetic-secret"));
        for (int i = 0; i < 2; i++) {
            headers.clear(); wrapped.applyCredentials(headers::put);
            require(headers.size() == 3 && "Bearer synthetic".equals(headers.get("Authorization"))
                && "synthetic-id".equals(headers.get("X-Gateway-Client-Id"))
                && "synthetic-secret".equals(headers.get("X-Gateway-Client-Secret")));
        }
        require(wrapped.shouldRetryRequest(null));
        headers.clear(); new AccessCredentials(delegate, Map.of()).applyCredentials(headers::put);
        require(headers.size() == 1);
        for (String name : new String[]{"GATEWAY_CLIENT_ID", "GATEWAY_CLIENT_SECRET"}) {
            try { new AccessCredentials(delegate, Map.of(name, "synthetic")); throw new AssertionError(); }
            catch (AccessCredentials.ConfigurationException e) {
                require(e.getMessage().equals("Missing " + (name.endsWith("_ID") ? "GATEWAY_CLIENT_SECRET" : "GATEWAY_CLIENT_ID")));
            }
        }
        var failing = new CredentialsProvider() {
            public void applyCredentials(CredentialsApplier a) throws IOException { throw new IOException("synthetic-sensitive-value"); }
            public boolean shouldRetryRequest(StatusCode c) { return false; }
        };
        try { new AccessCredentials(failing, Map.of()).applyCredentials(headers::put); throw new AssertionError(); }
        catch (IOException e) { require(e.getCause() == null && !e.getMessage().contains("synthetic-sensitive-value")); }
        System.out.println("Offline credential checks passed");
    }
    private static void require(boolean ok) { if (!ok) throw new AssertionError("Credential check failed"); }
}
