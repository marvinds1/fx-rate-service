package io.github.marvinds1.fx.grpc;

import com.google.protobuf.Timestamp;
import io.github.marvinds1.fx.api.v1.CreateQuoteRequest;
import io.github.marvinds1.fx.api.v1.FxServiceGrpc;
import io.github.marvinds1.fx.api.v1.ListRatesRequest;
import io.github.marvinds1.fx.api.v1.ListRatesResponse;
import io.github.marvinds1.fx.api.v1.Quote;
import io.github.marvinds1.fx.api.v1.RedeemQuoteRequest;
import io.github.marvinds1.fx.pricing.Pricer;
import io.github.marvinds1.fx.pricing.PricingException;
import io.github.marvinds1.fx.quote.LockedQuote;
import io.github.marvinds1.fx.quote.QuoteNotFoundException;
import io.github.marvinds1.fx.quote.QuoteService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FxGrpcService extends FxServiceGrpc.FxServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(FxGrpcService.class);
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final QuoteService quotes;
    private final Pricer pricer;

    public FxGrpcService(QuoteService quotes, Pricer pricer) {
        this.quotes = quotes;
        this.pricer = pricer;
    }

    @Override
    public void createQuote(CreateQuoteRequest request, StreamObserver<Quote> response) {
        respond(response, () -> toProto(quotes.create(
                currency(request.getSellCurrency(), "sell_currency"),
                currency(request.getBuyCurrency(), "buy_currency"),
                request.getSellAmountMinor())));
    }

    @Override
    public void redeemQuote(RedeemQuoteRequest request, StreamObserver<Quote> response) {
        respond(response, () -> {
            if (request.getQuoteId().isBlank()) {
                throw new PricingException("quote_id is required");
            }
            return toProto(quotes.redeem(request.getQuoteId()));
        });
    }

    @Override
    public void listRates(ListRatesRequest request, StreamObserver<ListRatesResponse> response) {
        respond(response, () -> {
            String base = currency(request.getBaseCurrency(), "base_currency");
            ListRatesResponse.Builder builder = ListRatesResponse.newBuilder().setBaseCurrency(base);
            pricer.midRates(base).forEach((code, rate) -> builder.putMidRates(code, rate.toPlainString()));
            return builder.build();
        });
    }

    private static <T> void respond(StreamObserver<T> observer, Supplier<T> call) {
        T result;
        try {
            result = call.get();
        } catch (PricingException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (QuoteNotFoundException e) {
            observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("unexpected error", e);
            observer.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
            return;
        }
        observer.onNext(result);
        observer.onCompleted();
    }

    private static String currency(String value, String field) {
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(code).matches()) {
            throw new PricingException(field + " must be a 3-letter ISO 4217 code");
        }
        return code;
    }

    private static Quote toProto(LockedQuote q) {
        return Quote.newBuilder()
                .setQuoteId(q.id())
                .setSellCurrency(q.sellCurrency())
                .setBuyCurrency(q.buyCurrency())
                .setSellAmountMinor(q.sellAmountMinor())
                .setBuyAmountMinor(q.buyAmountMinor())
                .setMidRate(q.midRate().toPlainString())
                .setCustomerRate(q.customerRate().toPlainString())
                .setSpreadBps(q.spreadBps())
                .setExpiresAt(Timestamp.newBuilder()
                        .setSeconds(q.expiresAt().getEpochSecond())
                        .setNanos(q.expiresAt().getNano()))
                .build();
    }
}
