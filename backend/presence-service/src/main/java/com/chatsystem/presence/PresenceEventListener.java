package com.chatsystem.presence;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.wire.PresenceStatus;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every presence instance receives every event and forwards it only to its own local
 * watchers, so nothing is delivered twice:
 * <ul>
 *   <li>{@code presence.user.*}: ONLINE (first heartbeat) or OFFLINE (logout)</li>
 *   <li>{@code __keyevent@0__:expired} for {@code presence:{id}}: OFFLINE after missed heartbeats</li>
 *   <li>{@code friends.changed.*}: re-read a connected user's friend list</li>
 * </ul>
 */
@Component
class PresenceEventListener implements MessageListener {

    private final PresenceHub hub;
    private final PresenceService presence;
    private final PresenceWebSocketHandler handler;
    private final JsonMapper json;

    PresenceEventListener(PresenceHub hub, PresenceService presence, PresenceWebSocketHandler handler, JsonMapper json) {
        this.hub = hub;
        this.presence = presence;
        this.handler = handler;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        if (RedisKeys.KEY_EXPIRED_CHANNEL.equals(channel)) {
            if (isPresenceKey(body)) {
                hub.notifyWatchers(presence.offlineStatus(RedisKeys.trailingId(body)));
            }
        } else if (channel.startsWith("presence.user.")) {
            hub.notifyWatchers(json.readValue(body, PresenceStatus.class));
        } else if (channel.startsWith("friends.changed.")) {
            handler.refreshFriends(RedisKeys.trailingId(channel));
        }
    }

    /** {@code presence:42} yes; {@code presence:last_active:42} or unrelated keys no. */
    static boolean isPresenceKey(String key) {
        return key.startsWith(RedisKeys.PRESENCE_PREFIX)
                && key.substring(RedisKeys.PRESENCE_PREFIX.length()).chars().allMatch(Character::isDigit);
    }
}
