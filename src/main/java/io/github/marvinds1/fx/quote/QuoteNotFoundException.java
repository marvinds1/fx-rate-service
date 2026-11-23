package io.github.marvinds1.fx.quote;

public class QuoteNotFoundException extends RuntimeException {

    public QuoteNotFoundException(String id) {
        super("quote " + id + " expired or was already redeemed");
    }
}
