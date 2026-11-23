package io.github.marvinds1.fx.pricing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Prices conversions from a USD-based rate table. Cross rates (e.g. IDR→SGD) go through USD.
 *
 * <p>Rates keep 16 significant digits; only the final amount is rounded, and always down
 * to the buy currency's minor unit, so rounding never pays out more than the rate allows.
 */
@Component
public class Pricer {

    private static final MathContext PRECISION = new MathContext(16, RoundingMode.HALF_EVEN);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    private final Map<String, BigDecimal> ratesPerUsd;
    private final int spreadBps;

    public Pricer(FxProperties properties) {
        this.ratesPerUsd = new TreeMap<>(properties.ratesPerUsd());
        this.ratesPerUsd.forEach((code, rate) -> {
            Currency.getInstance(code);
            if (rate.signum() <= 0) {
                throw new IllegalArgumentException("rate for " + code + " must be positive");
            }
        });
        this.spreadBps = properties.spreadBps();
    }

    public Price price(String sellCurrency, String buyCurrency, long sellAmountMinor) {
        if (sellAmountMinor <= 0) {
            throw new PricingException("sell_amount_minor must be positive");
        }
        if (sellCurrency.equals(buyCurrency)) {
            throw new PricingException("sell and buy currency must differ");
        }
        BigDecimal mid = midRate(sellCurrency, buyCurrency);
        BigDecimal customer = mid.multiply(BPS.subtract(BigDecimal.valueOf(spreadBps)), PRECISION)
                .divide(BPS, PRECISION);

        int sellDigits = Currency.getInstance(sellCurrency).getDefaultFractionDigits();
        int buyDigits = Currency.getInstance(buyCurrency).getDefaultFractionDigits();
        BigDecimal sellMajor = BigDecimal.valueOf(sellAmountMinor).movePointLeft(sellDigits);
        BigDecimal buyMinor = sellMajor.multiply(customer).movePointRight(buyDigits)
                .setScale(0, RoundingMode.DOWN);

        if (buyMinor.signum() == 0) {
            throw new PricingException("amount too small to convert");
        }
        try {
            return new Price(mid.stripTrailingZeros(), customer.stripTrailingZeros(), spreadBps,
                    buyMinor.longValueExact());
        } catch (ArithmeticException e) {
            throw new PricingException("amount too large to convert");
        }
    }

    /** Units of {@code buy} per 1 unit of {@code sell}. */
    public BigDecimal midRate(String sell, String buy) {
        return rate(buy).divide(rate(sell), PRECISION);
    }

    public Map<String, BigDecimal> midRates(String base) {
        Map<String, BigDecimal> result = new TreeMap<>();
        for (String code : ratesPerUsd.keySet()) {
            if (!code.equals(base)) {
                result.put(code, midRate(base, code).stripTrailingZeros());
            }
        }
        if (!ratesPerUsd.containsKey(base)) {
            throw new PricingException("unsupported currency " + base);
        }
        return result;
    }

    private BigDecimal rate(String code) {
        BigDecimal rate = ratesPerUsd.get(code);
        if (rate == null) {
            throw new PricingException("unsupported currency " + code);
        }
        return rate;
    }
}
