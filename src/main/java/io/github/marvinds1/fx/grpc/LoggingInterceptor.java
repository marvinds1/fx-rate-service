package io.github.marvinds1.fx.grpc;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One access-log line per call: client, method, status code and latency. Ordered after the
 * auth interceptor in the list, which makes it the outermost one, so rejected calls are logged too.
 */
@Component
@Order(2)
public class LoggingInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger("grpc.access");

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        long start = System.nanoTime();
        return next.startCall(new SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                log.info("{} {} {} {}ms", Objects.requireNonNullElse(ApiKeyInterceptor.CLIENT_ID.get(), "-"),
                        call.getMethodDescriptor().getFullMethodName(), status.getCode(),
                        (System.nanoTime() - start) / 1_000_000);
                super.close(status, trailers);
            }
        }, headers);
    }
}
