package com.chatsystem.chat.message;

import com.chatsystem.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The book's "local sequence number generator": ids only need to be unique and ordered
 * within a channel, so each channel gets its own Redis counter.
 */
@Component
public class MessageIdGenerator {

    private final StringRedisTemplate redis;
    private final MessageStore store;

    public MessageIdGenerator(StringRedisTemplate redis, MessageStore store) {
        this.redis = redis;
        this.store = store;
    }

    public long next(String channelId) {
        String key = RedisKeys.channelSequence(channelId);
        Long id = redis.opsForValue().increment(key);
        if (id == null) {
            throw new IllegalStateException("Redis returned no value for INCR " + key);
        }
        if (id == 1) {
            // Either a brand-new channel or the counter was lost (Redis flushed): continue after
            // the newest persisted message so ids never go backwards.
            long persistedMax = store.maxMessageId(channelId);
            if (persistedMax > 0) {
                id = redis.opsForValue().increment(key, persistedMax);
            }
        }
        return id;
    }
}
