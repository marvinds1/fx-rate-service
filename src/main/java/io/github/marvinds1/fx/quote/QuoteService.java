package io.github.marvinds1.fx.quote;

import io.github.marvinds1.fx.pricing.FxProperties;
import io.github.marvinds1.fx.pricing.Price;
import io.github.marvinds1.fx.pricing.Pricer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    public LockedQuote create(String sellCurrency, String buyCurrency, long sellAmountMinor) {
        Price price = pricer.price(sellCurrency, buyCurrency, sellAmountMinor);
        LockedQuote quote = new LockedQuote(UUID.randomUUID().toString(), sellCurrency, buyCurrency,
                sellAmountMinor, price.buyAmountMinor(), price.midRate(), price.customerRate(),
                price.spreadBps(), Instant.now(clock).plus(ttl));
        store.save(quote, ttl);
        return quote;
    }

    public LockedQuote redeem(String id) {
        LockedQuote quote = store.take(id).orElseThrow(() -> new QuoteNotFoundException(id));
        if (!Instant.now(clock).isBefore(quote.expiresAt())) {
            throw new QuoteNotFoundException(id);
        }
        return quote;
    }
}
