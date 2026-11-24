package io.github.marvinds1.fx.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the gRPC server inside the Spring lifecycle with health checks, optional reflection and
 * defensive limits: a bounded handler thread pool, small message and metadata limits, a cap on
 * concurrent calls per connection, and idle/age limits so connections are recycled and load
 * spreads across replicas.
 */
@Component
public class GrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);
    private static final int MAX_MESSAGE_BYTES = 16 * 1024;
    private static final int MAX_METADATA_BYTES = 8 * 1024;
    private static final int MAX_CALLS_PER_CONNECTION = 100;

    private final int port;
    private final boolean reflectionEnabled;
    private final ExecutorService executor;
    private final List<BindableService> services;
    private final List<ServerInterceptor> interceptors;
    private final HealthStatusManager health = new HealthStatusManager();
    private volatile Server server;

    public GrpcServer(@Value("${grpc.port:9090}") int port,
                      @Value("${grpc.reflection-enabled:false}") boolean reflectionEnabled,
                      @Value("${grpc.threads:32}") int threads,
                      List<BindableService> services, List<ServerInterceptor> interceptors) {
        this.port = port;
        this.reflectionEnabled = reflectionEnabled;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads,
                task -> new Thread(task, "grpc-" + counter.incrementAndGet()));
        this.services = services;
        this.interceptors = interceptors;
    }

    @Override
    public void start() {
        NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                .executor(executor)
                .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                .maxInboundMetadataSize(MAX_METADATA_BYTES)
                .maxConcurrentCallsPerConnection(MAX_CALLS_PER_CONNECTION)
                .maxConnectionIdle(5, TimeUnit.MINUTES)
                .maxConnectionAge(30, TimeUnit.MINUTES)
                .maxConnectionAgeGrace(30, TimeUnit.SECONDS)
                .permitKeepAliveTime(30, TimeUnit.SECONDS)
                .addService(health.getHealthService());
        if (reflectionEnabled) {
            builder.addService(ProtoReflectionServiceV1.newInstance());
        }
        services.forEach(s -> builder.addService(ServerInterceptors.intercept(s, interceptors)));
        try {
            server = builder.build().start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start gRPC server on port " + port, e);
        }
        health.setStatus("", ServingStatus.SERVING);
        log.info("gRPC server listening on port {}", server.getPort());
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        health.enterTerminalState();
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    public int port() {
        return server.getPort();
    }
}
