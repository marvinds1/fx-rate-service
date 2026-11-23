package io.github.marvinds1.fx.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PricerTest {

    private final Pricer pricer = new Pricer(new FxProperties(Map.of(
            "USD", BigDecimal.ONE,
            "IDR", new BigDecimal("16250"),
            "SGD", new BigDecimal("1.3420"),
            "JPY", new BigDecimal("151.80")), 50, Duration.ofSeconds(30)));

    @Test
    void convertsIdrToUsdWithSpreadAndRoundsDown() {
        Price price = pricer.price("IDR", "USD", 100_000_000L);

        assertThat(price.midRate()).isEqualByComparingTo("0.00006153846153846154");
        assertThat(price.customerRate()).isEqualByComparingTo("0.00006123076923076923");
        assertThat(price.buyAmountMinor()).isEqualTo(6123L);
        assertThat(price.spreadBps()).isEqualTo(50);
    }

    @Test
    void crossRateGoesThroughUsd() {
        Price price = pricer.price("SGD", "IDR", 10_000L);

        assertThat(price.midRate()).isEqualByComparingTo(new BigDecimal("16250").divide(new BigDecimal("1.342"),
                new java.math.MathContext(16)));
        assertThat(price.buyAmountMinor()).isEqualTo(120_482_488L);
    }

    @Test
    void respectsZeroDecimalCurrencies() {
        assertThat(pricer.price("USD", "JPY", 1_000L).buyAmountMinor()).isEqualTo(1510L);
        assertThat(pricer.price("JPY", "USD", 1_000L).buyAmountMinor()).isEqualTo(655L);
    }

    @Test
    void rejectsInvalidRequests() {
        assertThatThrownBy(() -> pricer.price("IDR", "USD", 0)).hasMessageContaining("positive");
        assertThatThrownBy(() -> pricer.price("USD", "USD", 100)).hasMessageContaining("must differ");
        assertThatThrownBy(() -> pricer.price("USD", "GBP", 100)).hasMessageContaining("unsupported currency GBP");
        assertThatThrownBy(() -> pricer.price("IDR", "USD", 1)).hasMessageContaining("too small");
        assertThatThrownBy(() -> pricer.price("USD", "IDR", Long.MAX_VALUE)).hasMessageContaining("too large");
    }

    @Test
    void listsMidRatesForABase() {
        assertThat(pricer.midRates("USD")).containsOnlyKeys("IDR", "SGD", "JPY")
                .extractingByKey("IDR").satisfies(r -> assertThat(r).isEqualByComparingTo("16250"));
    }
}
