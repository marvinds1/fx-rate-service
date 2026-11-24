package io.github.marvinds1.fx.quote;

import io.github.marvinds1.fx.pricing.FxProperties;
import io.github.marvinds1.fx.pricing.Price;
import io.github.marvinds1.fx.pricing.Pricer;
import io.github.marvinds1.fx.pricing.PricingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class QuoteService {

    private final Pricer pricer;
    private final QuoteStore store;
    private final Clock clock;
    private final Duration ttl;

    public QuoteService(Pricer pricer, QuoteStore store, Clock clock, FxProperties properties) {
        this.pricer = pricer;
        this.store = store;
        this.clock = clock;
        this.ttl = properties.quoteTtl();
    }

    /**
     * Prices and locks a quote for {@code client}. With a request id, a retry within the quote
     * TTL returns the original quote; reusing the id for different parameters is rejected.
     */
    public LockedQuote create(String client, String sellCurrency, String buyCurrency, long sellAmountMinor,
                              String requestId) {
        Price price = pricer.price(sellCurrency, buyCurrency, sellAmountMinor);
        LockedQuote quote = new LockedQuote(UUID.randomUUID().toString(), sellCurrency, buyCurrency,
                sellAmountMinor, price.buyAmountMinor(), price.midRate(), price.customerRate(),
                price.spreadBps(), Instant.now(clock).plus(ttl));
        if (requestId == null || requestId.isEmpty()) {
            store.save(client, quote, ttl);
            return quote;
        }
        Optional<LockedQuote> earlier = store.saveOnce(client, requestId, quote, ttl);
        if (earlier.isEmpty()) {
            return quote;
        }
        LockedQuote previous = earlier.get();
        if (!previous.sellCurrency().equals(sellCurrency) || !previous.buyCurrency().equals(buyCurrency)
                || previous.sellAmountMinor() != sellAmountMinor) {
            throw new PricingException("request_id was already used with different parameters");
        }
        return previous;
    }

    public LockedQuote redeem(String client, String id) {
        LockedQuote quote = store.take(client, id).orElseThrow(() -> new QuoteNotFoundException(id));
        if (!Instant.now(clock).isBefore(quote.expiresAt())) {
            throw new QuoteNotFoundException(id);
        }
        return quote;
    }
}
