package io.github.marvinds1.fx.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Quotes live in Redis with a TTL, namespaced per client: {@code fx:{client}:quote:{id}}.
 * <ul>
 *   <li>Redeeming uses {@code GETDEL}, so a quote is consumed at most once even under concurrent
 *       redeems; a different client looks up a different key and can neither read nor burn it.</li>
 *   <li>Idempotent creation stores the request id and the quote in one Lua script, so two
 *       concurrent retries cannot both create a quote.</li>
 *   <li>The {@code {client}} hash tag keeps a client's keys in one slot for Redis Cluster.</li>
 * </ul>
 */
@Repository
public class QuoteStore {

    private static final RedisScript<String> SAVE_ONCE = RedisScript.of("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
              return false
            end
            return redis.call('GET', KEYS[1])
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public QuoteStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void save(String client, LockedQuote quote, Duration ttl) {
        redis.opsForValue().set(quoteKey(client, quote.id()), write(quote), ttl);
    }

    /**
     * Saves {@code quote} unless {@code requestId} was already used by this client within the TTL;
     * returns the earlier quote in that case.
     */
    public Optional<LockedQuote> saveOnce(String client, String requestId, LockedQuote quote, Duration ttl) {
        String existing = redis.execute(SAVE_ONCE,
                List.of(requestKey(client, requestId), quoteKey(client, quote.id())),
                write(quote), Long.toString(ttl.toMillis()));
        return Optional.ofNullable(existing).map(this::read);
    }

    public Optional<LockedQuote> take(String client, String id) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(quoteKey(client, id))).map(this::read);
    }

    private static String quoteKey(String client, String id) {
        return "fx:{" + client + "}:quote:" + id;
    }

    private static String requestKey(String client, String requestId) {
        return "fx:{" + client + "}:request:" + requestId;
    }

    private String write(LockedQuote quote) {
        try {
            return json.writeValueAsString(quote);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private LockedQuote read(String value) {
        try {
            return json.readValue(value, LockedQuote.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
