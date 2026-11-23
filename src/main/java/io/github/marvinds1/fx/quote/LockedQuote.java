package io.github.marvinds1.fx.quote;

import java.math.BigDecimal;
import java.time.Instant;

public record LockedQuote(
        String id,
        String sellCurrency,
        String buyCurrency,
        long sellAmountMinor,
        long buyAmountMinor,
        BigDecimal midRate,
        BigDecimal customerRate,
        int spreadBps,
        Instant expiresAt) {
}
