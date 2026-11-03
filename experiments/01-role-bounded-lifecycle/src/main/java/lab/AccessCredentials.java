package lab;

import io.camunda.client.CredentialsProvider;
import io.camunda.client.impl.NoopCredentialsProvider;
import java.io.IOException;
import java.util.Map;

/** Engine request decoration; the library's separate OAuth connection is not intercepted. */
final class AccessCredentials implements CredentialsProvider {
    private final CredentialsProvider delegate;
    private final String id;
    private final String secret;

    AccessCredentials(CredentialsProvider delegate, Map<String, String> env) {
        this.delegate = delegate;
        id = env.get("GATEWAY_CLIENT_ID");
        secret = env.get("GATEWAY_CLIENT_SECRET");
        if (id != null && secret == null) throw new ConfigurationException("Missing GATEWAY_CLIENT_SECRET");
        if (secret != null && id == null) throw new ConfigurationException("Missing GATEWAY_CLIENT_ID");
        validate("GATEWAY_CLIENT_ID", id);
        validate("GATEWAY_CLIENT_SECRET", secret);
    }

    private static void validate(String name, String value) {
        if (value != null && (value.isBlank() || value.chars().anyMatch(c -> c < 32 || c > 126)))
            throw new ConfigurationException("Invalid " + name + ": expected a nonempty printable ASCII value");
    }

    static CredentialsProvider fromEnvironment() {
        var env = System.getenv();
        // Validate before building OAuth (which may perform discovery).
        new AccessCredentials(new NoopCredentialsProvider(), env);
        // Match CamundaClientBuilderImpl 8.10.7: presence, including empty values;
        // OAuth wins over basic. Provider builders retain their normal env handling.
        CredentialsProvider delegate = new NoopCredentialsProvider();
        if (env.containsKey("CAMUNDA_CLIENT_ID") && env.containsKey("CAMUNDA_CLIENT_SECRET"))
            delegate = CredentialsProvider.newCredentialsProviderBuilder().build();
        else if (env.containsKey("CAMUNDA_BASIC_AUTH_USERNAME") && env.containsKey("CAMUNDA_BASIC_AUTH_PASSWORD"))
            delegate = CredentialsProvider.newBasicAuthCredentialsProviderBuilder().build();
        return new AccessCredentials(delegate, env);
    }

    static void requireRestEnvironment() {
        var env = System.getenv();
        if (env.containsKey("CAMUNDA_PREFER_REST") && !"true".equals(env.get("CAMUNDA_PREFER_REST")))
            throw new ConfigurationException("CAMUNDA_PREFER_REST must be true for this REST-only lab");
        for (String name : new String[]{"CAMUNDA_CLIENT_WORKER_STREAM_ENABLED", "CAMUNDA_CLIENT_USE_CLIENT_SIDE_LOAD_BALANCING"})
            if ("true".equals(env.get(name)))
                throw new ConfigurationException(name + " must be false for this REST-only lab");
        if (env.containsKey("CAMUNDA_GRPC_ADDRESS") && !"http://127.0.0.1:26500".equals(env.get("CAMUNDA_GRPC_ADDRESS")))
            throw new ConfigurationException("Unset CAMUNDA_GRPC_ADDRESS for this REST-only lab");
    }

    public void applyCredentials(CredentialsApplier applier) throws IOException {
        try {
            delegate.applyCredentials(applier);
            if (id != null) {
                applier.put("X-Gateway-Client-Id", id);
                applier.put("X-Gateway-Client-Secret", secret);
            }
        } catch (IOException | RuntimeException e) {
            // Do not retain a cause whose message could contain tokens or echoed headers.
            throw new IOException("Credential application failed; check authentication configuration");
        }
    }

    public boolean shouldRetryRequest(StatusCode code) { return delegate.shouldRetryRequest(code); }

    static final class ConfigurationException extends IllegalArgumentException {
        ConfigurationException(String message) { super(message); }
    }
}
