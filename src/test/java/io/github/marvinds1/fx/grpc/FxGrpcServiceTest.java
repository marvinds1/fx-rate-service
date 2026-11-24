package io.github.marvinds1.fx.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.marvinds1.fx.api.v1.CreateQuoteRequest;
import io.github.marvinds1.fx.api.v1.Quote;
import io.github.marvinds1.fx.pricing.Pricer;
import io.github.marvinds1.fx.quote.QuoteService;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class FxGrpcServiceTest {

    @Test
    void redisOutageIsReportedAsRetryableUnavailable() {
        QuoteService quotes = mock(QuoteService.class);
        when(quotes.create(anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        var service = new FxGrpcService(quotes, mock(Pricer.class));
        AtomicReference<Throwable> error = new AtomicReference<>();

        Context.current().withValue(ApiKeyInterceptor.CLIENT_ID, "mobile").run(() -> service.createQuote(
                CreateQuoteRequest.newBuilder().setSellCurrency("IDR").setBuyCurrency("USD").setSellAmountMinor(1).build(),
                new StreamObserver<Quote>() {
                    public void onNext(Quote value) { }

                    public void onError(Throwable t) {
                        error.set(t);
                    }

                    public void onCompleted() { }
                }));

        assertThat(Status.fromThrowable(error.get()).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }
}
