package io.github.marvinds1.fx.pricing;

import java.math.BigDecimal;

public record Price(BigDecimal midRate, BigDecimal customerRate, int spreadBps, long buyAmountMinor) {
}
