package com.chatsystem.chat.message;

import com.chatsystem.common.RedisKeys;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The book's "local sequence number generator": ids only need to be unique and ordered
 * within a channel, so each channel gets its own Redis counter.
 *
 * <p>api-service creates the counter at 0 together with the channel, so the normal path is a
 * single INCR. Only a missing counter (Redis was flushed, or the channel predates that) costs
 * a Cassandra read, to continue after the newest persisted message so ids never go backwards.
 */
@Component
public class MessageIdGenerator {

    /** INCR, but only if the counter exists; nil tells us it must be seeded first. */
    static final RedisScript<Long> INCR_IF_EXISTS = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('INCR', KEYS[1])
            end
            return nil""", Long.class);

    /** Seeds the counter unless a concurrent sender already did, then INCRs: racing seeders get distinct ids. */
    static final RedisScript<Long> SEED_AND_INCR = RedisScript.of("""
            redis.call('SET', KEYS[1], ARGV[1], 'NX')
            return redis.call('INCR', KEYS[1])""", Long.class);

    private final StringRedisTemplate redis;
    private final MessageStore store;

    public MessageIdGenerator(StringRedisTemplate redis, MessageStore store) {
        this.redis = redis;
        this.store = store;
    }

    public long next(String channelId) {
        List<String> key = List.of(RedisKeys.channelSequence(channelId));
        Long id = redis.execute(INCR_IF_EXISTS, key);
        if (id != null) {
            return id;
        }
        Long seeded = redis.execute(SEED_AND_INCR, key, String.valueOf(store.maxMessageId(channelId)));
        if (seeded == null) {
            throw new IllegalStateException("Redis returned no value when seeding " + key.getFirst());
        }
        return seeded;
    }
}
