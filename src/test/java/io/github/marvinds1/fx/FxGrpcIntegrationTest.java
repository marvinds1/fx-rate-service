package io.github.marvinds1.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marvinds1.fx.api.v1.CreateQuoteRequest;
import io.github.marvinds1.fx.api.v1.FxServiceGrpc;
import io.github.marvinds1.fx.api.v1.ListRatesRequest;
import io.github.marvinds1.fx.api.v1.Quote;
import io.github.marvinds1.fx.api.v1.RedeemQuoteRequest;
import io.github.marvinds1.fx.grpc.GrpcServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"grpc.port=0", "fx.quote-ttl=2s"})
@Testcontainers
class FxGrpcIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Autowired
    GrpcServer server;

    ManagedChannel channel;
    FxServiceGrpc.FxServiceBlockingStub fx;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        fx = FxServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private Quote quote() {
        return fx.createQuote(CreateQuoteRequest.newBuilder()
                .setSellCurrency("IDR").setBuyCurrency("USD").setSellAmountMinor(100_000_000L).build());
    }

    private Quote redeem(String id) {
        return fx.redeemQuote(RedeemQuoteRequest.newBuilder().setQuoteId(id).build());
    }

    private static Status.Code codeOf(Runnable call) {
        try {
            call.run();
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    @Test
    void createsAndRedeemsALockedQuote() {
        Quote created = quote();

        assertThat(created.getBuyAmountMinor()).isEqualTo(6123L);
        assertThat(created.getCustomerRate()).isEqualTo("0.00006123076923076923");
        assertThat(created.getExpiresAt().getSeconds()).isPositive();

        assertThat(redeem(created.getQuoteId())).isEqualTo(created);
    }

    @Test
    void quoteCanBeRedeemedOnlyOnceEvenUnderConcurrency() throws Exception {
        String id = quote().getQuoteId();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Status.Code>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(pool.submit(() -> codeOf(() -> redeem(id))));
        }
        List<Status.Code> codes = new ArrayList<>();
        for (Future<Status.Code> f : attempts) {
            codes.add(f.get());
        }
        pool.shutdown();

        assertThat(codes).containsOnlyOnce(Status.Code.OK)
                .filteredOn(c -> c != Status.Code.OK).containsOnly(Status.Code.NOT_FOUND);
    }

    @Test
    void expiredQuoteCannotBeRedeemed() throws InterruptedException {
        String id = quote().getQuoteId();
        Thread.sleep(2_200);

        assertThat(codeOf(() -> redeem(id))).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void invalidRequestsAreRejectedWithInvalidArgument() {
        assertThatThrownBy(() -> fx.createQuote(CreateQuoteRequest.newBuilder()
                .setSellCurrency("rupiah").setBuyCurrency("USD").setSellAmountMinor(100).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("sell_currency");
                });
        assertThat(codeOf(() -> fx.createQuote(CreateQuoteRequest.newBuilder()
                .setSellCurrency("idr").setBuyCurrency("GBP").setSellAmountMinor(100).build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(codeOf(() -> redeem(""))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void listsMidRatesAndReportsHealth() {
        var rates = fx.listRates(ListRatesRequest.newBuilder().setBaseCurrency("USD").build());
        assertThat(rates.getMidRatesMap()).containsEntry("IDR", "16250").doesNotContainKey("USD");

        var health = HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());
        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }
}
