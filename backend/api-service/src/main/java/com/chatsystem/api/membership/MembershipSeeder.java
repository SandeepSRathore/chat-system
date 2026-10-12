package com.chatsystem.api.membership;

import com.chatsystem.common.ChannelIds;
import com.chatsystem.common.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Rebuilds the Redis membership sets from PostgreSQL on startup so Redis can be flushed safely. */
@Component
class MembershipSeeder {

    private static final Logger log = LoggerFactory.getLogger(MembershipSeeder.class);

    private record Pair(long a, long b) {
    }

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;

    MembershipSeeder(JdbcClient jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        List<Pair> friendships = jdbc.sql("SELECT user_id AS a, friend_id AS b FROM friendships").query(Pair.class).list();
        List<Pair> groupMembers = jdbc.sql("SELECT group_id AS a, user_id AS b FROM group_members").query(Pair.class).list();

        redis.executePipelined((RedisCallback<Object>) connection -> {
            var sets = connection.setCommands();
            for (Pair f : friendships) {
                sets.sAdd(bytes(RedisKeys.friends(f.a())), bytes(String.valueOf(f.b())));
                sets.sAdd(bytes(RedisKeys.channelMembers(ChannelIds.direct(f.a(), f.b()))), bytes(String.valueOf(f.a())));
            }
            for (Pair m : groupMembers) {
                sets.sAdd(bytes(RedisKeys.channelMembers(ChannelIds.group(m.a()))), bytes(String.valueOf(m.b())));
            }
            return null;
        });
        log.info("Seeded Redis membership: {} friendship rows, {} group-member rows", friendships.size(), groupMembers.size());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
