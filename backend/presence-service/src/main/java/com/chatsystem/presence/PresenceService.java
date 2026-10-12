package com.chatsystem.presence;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.wire.PresenceStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Online status lives in {@code presence:{userId}}, a key whose TTL every heartbeat renews.
 * Online transitions are published here; offline transitions are the key's expiry event
 * (see {@link PresenceEventListener}), so a crashed client or server needs no cleanup.
 */
@Service
public class PresenceService {

    private static final String ONLINE = "1";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public PresenceService(StringRedisTemplate redis, JsonMapper json, PresenceProperties props) {
        this(redis, json, props.presence().ttl(), Clock.systemUTC());
    }

    PresenceService(StringRedisTemplate redis, JsonMapper json, Duration ttl, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Renews the user's online key; publishes ONLINE if they were offline. */
    public void heartbeat(long userId) {
        long now = clock.millis();
        redis.opsForValue().set(RedisKeys.lastActive(userId), String.valueOf(now));
        // SET … EX … GET is atomic: a null previous value means this heartbeat brought the user online.
        String previous = redis.opsForValue().setGet(RedisKeys.presence(userId), ONLINE, ttl);
        if (previous == null) {
            publish(new PresenceStatus(userId, true, now));
        }
    }

    /** Explicit logout: offline immediately instead of waiting for the TTL. */
    public void goOffline(long userId) {
        long now = clock.millis();
        redis.opsForValue().set(RedisKeys.lastActive(userId), String.valueOf(now));
        if (Boolean.TRUE.equals(redis.delete(RedisKeys.presence(userId)))) {
            publish(new PresenceStatus(userId, false, now));
        }
    }

    public PresenceStatus offlineStatus(long userId) {
        String lastActive = redis.opsForValue().get(RedisKeys.lastActive(userId));
        return new PresenceStatus(userId, false, lastActive == null ? 0 : Long.parseLong(lastActive));
    }

    public List<PresenceStatus> statuses(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = List.copyOf(userIds);
        List<String> keys = new ArrayList<>(ids.size() * 2);
        ids.forEach(id -> keys.add(RedisKeys.presence(id)));
        ids.forEach(id -> keys.add(RedisKeys.lastActive(id)));
        List<String> values = redis.opsForValue().multiGet(keys);
        List<PresenceStatus> result = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String online = values == null ? null : values.get(i);
            String lastActive = values == null ? null : values.get(ids.size() + i);
            result.add(new PresenceStatus(ids.get(i), online != null, lastActive == null ? 0 : Long.parseLong(lastActive)));
        }
        return result;
    }

    private void publish(PresenceStatus status) {
        redis.convertAndSend(RedisKeys.presenceEvents(status.userId()), json.writeValueAsString(status));
    }
}
