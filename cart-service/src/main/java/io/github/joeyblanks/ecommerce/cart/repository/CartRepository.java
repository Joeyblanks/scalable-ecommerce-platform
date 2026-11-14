package io.github.joeyblanks.ecommerce.cart.repository;

import io.github.joeyblanks.ecommerce.cart.config.CartProperties;
import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis layout: one HASH per user at {@code cart:<userId>}; one field per product id; the value is
 * the {@link CartItem} as JSON. The key's TTL is the inactivity expiry and is renewed on every access.
 */
@Repository
public class CartRepository {

    /** What happened to a conditional write. */
    public enum WriteResult { WRITTEN, CHANGED_CONCURRENTLY, CART_FULL }

    /** A stored line together with its exact stored text, which is the "version" used for compare-and-set. */
    public record Stored(CartItem item, String raw) {
    }

    /**
     * Compare-and-set, executed atomically inside Redis (nothing can interleave with a script).
     * KEYS[1] cart key; ARGV: field, expected raw value ('' = the line must not exist yet), new value,
     * ttl seconds, max lines. Returns 1 written, 0 the line changed since it was read, -1 cart full.
     */
    private static final DefaultRedisScript<Long> COMPARE_AND_SET = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if ARGV[2] == '' then
              if current then return 0 end
              if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[5]) then return -1 end
            else
              if (not current) or current ~= ARGV[2] then return 0 end
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CartProperties properties;

    public CartRepository(StringRedisTemplate redis, JsonMapper json, CartProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public static String key(UUID userId) {
        return "cart:" + userId;
    }

    /** All lines of the cart. Renews the inactivity timer. An unknown cart is simply empty. */
    public List<CartItem> findAll(UUID userId) {
        return guard(() -> {
            String key = key(userId);
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            if (!entries.isEmpty()) {
                redis.expire(key, properties.ttl());
            }
            List<CartItem> items = new ArrayList<>(entries.size());
            entries.values().forEach(raw -> items.add(read((String) raw)));
            return items;
        });
    }

    /** One line (with its raw stored text). Renews the inactivity timer when the cart exists. */
    public Optional<Stored> find(UUID userId, UUID productId) {
        return guard(() -> {
            String key = key(userId);
            Object raw = redis.opsForHash().get(key, productId.toString());
            if (raw == null) {
                return Optional.empty();
            }
            redis.expire(key, properties.ttl());
            return Optional.of(new Stored(read((String) raw), (String) raw));
        });
    }

    /**
     * Writes {@code item} only if the line is still exactly as it was when read ({@code expected}),
     * or still absent when {@code expected} is null. See ADR 006 for why.
     */
    public WriteResult writeIfUnchanged(UUID userId, Stored expected, CartItem item) {
        return guard(() -> {
            Long result = redis.execute(COMPARE_AND_SET, List.of(key(userId)),
                    item.productId().toString(),
                    expected == null ? "" : expected.raw(),
                    write(item),
                    String.valueOf(properties.ttl().toSeconds()),
                    String.valueOf(properties.maxLines()));
            if (result == null) {
                throw new IllegalStateException("Redis script returned no result");
            }
            return switch (result.intValue()) {
                case 1 -> WriteResult.WRITTEN;
                case -1 -> WriteResult.CART_FULL;
                default -> WriteResult.CHANGED_CONCURRENTLY;
            };
        });
    }

    /** @return true if the line existed */
    public boolean remove(UUID userId, UUID productId) {
        return guard(() -> {
            Long removed = redis.opsForHash().delete(key(userId), productId.toString());
            if (removed != null && removed > 0) {
                redis.expire(key(userId), properties.ttl());
            }
            return removed != null && removed > 0;
        });
    }

    public void clear(UUID userId) {
        guard(() -> redis.delete(key(userId)));
    }

    private String write(CartItem item) {
        try {
            return json.writeValueAsString(item);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise cart item", e);
        }
    }

    private CartItem read(String raw) {
        try {
            return json.readValue(raw, CartItem.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt cart item in Redis", e);
        }
    }

    /** Turns any Redis connectivity/timeout problem into one clear, mappable exception. */
    private static <T> T guard(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            throw new CartStorageUnavailableException(e);
        }
    }
}
