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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs the gRPC server inside the Spring lifecycle, with health checks and reflection. */
@Component
public class GrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final int port;
    private final List<BindableService> services;
    private final List<ServerInterceptor> interceptors;
    private final HealthStatusManager health = new HealthStatusManager();
    private volatile Server server;

    public GrpcServer(@Value("${grpc.port:9090}") int port, List<BindableService> services,
                      List<ServerInterceptor> interceptors) {
        this.port = port;
        this.services = services;
        this.interceptors = interceptors;
    }

    @Override
    public void start() {
        NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                .addService(health.getHealthService())
                .addService(ProtoReflectionServiceV1.newInstance());
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
