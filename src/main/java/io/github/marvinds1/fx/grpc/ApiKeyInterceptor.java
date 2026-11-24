package io.github.marvinds1.fx.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Authenticates calls with an {@code x-api-key} header and exposes the caller's client id in
 * the gRPC {@link Context}. Keys are configured as {@code client-id:secret} pairs
 * ({@code FX_API_KEYS}); several secrets per client allow rotation. Comparison is constant
 * time. Health and reflection services are not intercepted.
 */
@Component
@Order(1)
public class ApiKeyInterceptor implements ServerInterceptor {

    public static final Context.Key<String> CLIENT_ID = Context.key("fx-client-id");
    static final Metadata.Key<String> API_KEY = Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);
    static final String ANONYMOUS = "anonymous";
    private static final int MIN_SECRET_LENGTH = 16;
    private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);

    private final Map<byte[], String> clientBySecret = new LinkedHashMap<>();

    public ApiKeyInterceptor(@Value("${grpc.api-keys:}") String configured) {
        for (String pair : configured.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.strip().split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{1,32}") || parts[1].length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException(
                        "grpc.api-keys entries must be client-id:secret with a secret of at least "
                                + MIN_SECRET_LENGTH + " characters");
            }
            clientBySecret.put(parts[1].getBytes(StandardCharsets.UTF_8), parts[0]);
        }
        if (clientBySecret.isEmpty()) {
            log.warn("grpc.api-keys is empty: authentication is DISABLED, all callers share one client id");
        }
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String client = clientBySecret.isEmpty() ? ANONYMOUS : clientFor(headers.get(API_KEY));
        if (client == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid API key"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return Contexts.interceptCall(Context.current().withValue(CLIENT_ID, client), call, headers, next);
    }

    private String clientFor(String supplied) {
        if (supplied == null) {
            return null;
        }
        byte[] candidate = supplied.getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (Map.Entry<byte[], String> entry : clientBySecret.entrySet()) {
            if (MessageDigest.isEqual(candidate, entry.getKey())) {
                match = entry.getValue();
            }
        }
        return match;
    }
}
