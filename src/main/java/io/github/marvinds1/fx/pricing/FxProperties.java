package io.github.marvinds1.fx.pricing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param ratesPerUsd units of each currency per 1 USD (mid market)
 * @param spreadBps   margin taken from the mid rate, in basis points
 * @param quoteTtl    how long a created quote can be redeemed
 * @param maxQuoteUsd largest amount (USD equivalent of the sell side) one quote may cover
 */
@Validated
@ConfigurationProperties(prefix = "fx")
public record FxProperties(
        @NotEmpty Map<String, BigDecimal> ratesPerUsd,
        @Min(0) @Max(1000) int spreadBps,
        @NotNull Duration quoteTtl,
        @NotNull @Positive BigDecimal maxQuoteUsd) {
}
