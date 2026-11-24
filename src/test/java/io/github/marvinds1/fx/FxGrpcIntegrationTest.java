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
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.MetadataUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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

@SpringBootTest(properties = {
        "grpc.port=0",
        "fx.quote-ttl=2s",
        "grpc.api-keys=mobile:mobile-secret-0123456789,ledger:ledger-secret-0123456789"})
@Testcontainers
class FxGrpcIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Autowired
    GrpcServer server;

    ManagedChannel channel;
    FxServiceGrpc.FxServiceBlockingStub mobile;
    FxServiceGrpc.FxServiceBlockingStub ledger;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        mobile = stubWithKey("mobile-secret-0123456789");
        ledger = stubWithKey("ledger-secret-0123456789");
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    private FxServiceGrpc.FxServiceBlockingStub stubWithKey(String key) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), key);
        return FxServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static CreateQuoteRequest.Builder idrToUsd() {
        return CreateQuoteRequest.newBuilder().setSellCurrency("IDR").setBuyCurrency("USD").setSellAmountMinor(100_000_000L);
    }

    private static RedeemQuoteRequest redeemOf(String id) {
        return RedeemQuoteRequest.newBuilder().setQuoteId(id).build();
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
        Quote created = mobile.createQuote(idrToUsd().build());

        assertThat(created.getBuyAmountMinor()).isEqualTo(6123L);
        assertThat(created.getCustomerRate()).isEqualTo("0.00006123076923076923");
        assertThat(mobile.redeemQuote(redeemOf(created.getQuoteId()))).isEqualTo(created);
    }

    @Test
    void rejectsCallsWithoutAValidKeyButKeepsHealthOpen() {
        var anonymous = FxServiceGrpc.newBlockingStub(channel);
        assertThat(codeOf(() -> anonymous.createQuote(idrToUsd().build()))).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(codeOf(() -> stubWithKey("not-a-valid-key-123456").listRates(
                ListRatesRequest.newBuilder().setBaseCurrency("USD").build()))).isEqualTo(Status.Code.UNAUTHENTICATED);

        var health = HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());
        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void anotherClientCanNeitherRedeemNorBurnAQuote() {
        String id = mobile.createQuote(idrToUsd().build()).getQuoteId();

        assertThat(codeOf(() -> ledger.redeemQuote(redeemOf(id)))).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(mobile.redeemQuote(redeemOf(id)).getQuoteId()).isEqualTo(id);
    }

    @Test
    void quoteCanBeRedeemedOnlyOnceEvenUnderConcurrency() throws Exception {
        String id = mobile.createQuote(idrToUsd().build()).getQuoteId();

        List<Status.Code> codes = runConcurrently(8, () -> codeOf(() -> mobile.redeemQuote(redeemOf(id))));

        assertThat(codes).containsOnlyOnce(Status.Code.OK)
                .filteredOn(c -> c != Status.Code.OK).containsOnly(Status.Code.NOT_FOUND);
    }

    @Test
    void retriedCreateWithSameRequestIdReturnsTheSameQuoteEvenConcurrently() throws Exception {
        List<String> ids = runConcurrently(8,
                () -> mobile.createQuote(idrToUsd().setRequestId("checkout-42").build()).getQuoteId());

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(mobile.redeemQuote(redeemOf(ids.get(0))).getQuoteId()).isEqualTo(ids.get(0));

        String otherClients = ledger.createQuote(idrToUsd().setRequestId("checkout-42").build()).getQuoteId();
        assertThat(otherClients).isNotEqualTo(ids.get(0));
    }

    @Test
    void reusingARequestIdWithDifferentParametersIsRejected() {
        mobile.createQuote(idrToUsd().setRequestId("order-7").build());

        assertThat(codeOf(() -> mobile.createQuote(idrToUsd().setRequestId("order-7").setSellAmountMinor(5).build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void expiredQuoteCannotBeRedeemed() throws InterruptedException {
        String id = mobile.createQuote(idrToUsd().build()).getQuoteId();
        Thread.sleep(2_200);

        assertThat(codeOf(() -> mobile.redeemQuote(redeemOf(id)))).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void invalidRequestsAreRejectedWithInvalidArgument() {
        assertThatThrownBy(() -> mobile.createQuote(idrToUsd().setSellCurrency("rupiah").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("sell_currency");
                });
        assertThat(codeOf(() -> mobile.createQuote(idrToUsd().setBuyCurrency("GBP").build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(codeOf(() -> mobile.createQuote(idrToUsd().setSellAmountMinor(Long.MAX_VALUE).build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(codeOf(() -> mobile.createQuote(idrToUsd().setRequestId("bad id with spaces").build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(codeOf(() -> mobile.redeemQuote(redeemOf("../../etc")))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void oversizedMessagesAreRejected() {
        assertThat(codeOf(() -> mobile.createQuote(idrToUsd().setRequestId("x".repeat(20_000)).build())))
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }

    @Test
    void listsMidRates() {
        var rates = mobile.listRates(ListRatesRequest.newBuilder().setBaseCurrency("USD").build());

        assertThat(rates.getMidRatesMap()).containsEntry("IDR", "16250").doesNotContainKey("USD");
    }

    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
