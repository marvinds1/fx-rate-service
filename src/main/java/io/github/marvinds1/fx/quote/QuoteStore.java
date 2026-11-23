package io.github.marvinds1.fx.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** Quotes live in Redis with a TTL; redeeming uses GETDEL so a quote can be consumed only once. */
@Repository
public class QuoteStore {

    private static final String PREFIX = "fx:quote:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public QuoteStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void save(LockedQuote quote, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + quote.id(), json.writeValueAsString(quote), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<LockedQuote> take(String id) {
        String value = redis.opsForValue().getAndDelete(PREFIX + id);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, LockedQuote.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
