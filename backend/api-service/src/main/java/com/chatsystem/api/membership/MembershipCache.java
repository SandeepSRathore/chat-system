package com.chatsystem.api.membership;

import com.chatsystem.common.ChannelIds;
import com.chatsystem.common.RedisKeys;
import java.util.Collection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL is the source of truth for friendships and group membership; this keeps the Redis
 * copies that chat servers (authorize + fan-out) and presence servers (who sees whom) read on
 * their hot path.
 */
@Component
public class MembershipCache {

    private final StringRedisTemplate redis;

    public MembershipCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void friendshipAdded(long userA, long userB) {
        redis.opsForSet().add(RedisKeys.friends(userA), String.valueOf(userB));
        redis.opsForSet().add(RedisKeys.friends(userB), String.valueOf(userA));
        redis.opsForSet().add(RedisKeys.channelMembers(ChannelIds.direct(userA, userB)),
                String.valueOf(userA), String.valueOf(userB));
        // Presence servers re-read the friend list of connected users on this signal.
        redis.convertAndSend(RedisKeys.friendsChanged(userA), "");
        redis.convertAndSend(RedisKeys.friendsChanged(userB), "");
    }

    public void groupMembersAdded(long groupId, Collection<Long> userIds) {
        if (!userIds.isEmpty()) {
            redis.opsForSet().add(RedisKeys.channelMembers(ChannelIds.group(groupId)),
                    userIds.stream().map(String::valueOf).toArray(String[]::new));
        }
    }

    public void groupMemberRemoved(long groupId, long userId) {
        redis.opsForSet().remove(RedisKeys.channelMembers(ChannelIds.group(groupId)), String.valueOf(userId));
    }
}
