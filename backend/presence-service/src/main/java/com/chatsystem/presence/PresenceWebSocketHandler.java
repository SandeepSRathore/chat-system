package com.chatsystem.presence;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.auth.AuthUser;
import com.chatsystem.common.auth.JwtHandshakeInterceptor;
import com.chatsystem.common.wire.ClientFrame;
import com.chatsystem.common.wire.ServerFrames;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PresenceWebSocketHandler extends TextWebSocketHandler {

    private final PresenceHub hub;
    private final PresenceService presence;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public PresenceWebSocketHandler(PresenceHub hub, PresenceService presence, StringRedisTemplate redis, JsonMapper json) {
        this.hub = hub;
        this.presence = presence;
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long userId = user(session).id();
        Set<Long> friends = loadFriends(userId);
        hub.connect(userId, session, friends);
        presence.heartbeat(userId);
        sendSnapshot(userId, friends);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            ClientFrame frame = json.readValue(message.getPayload(), ClientFrame.class);
            if (ClientFrame.HEARTBEAT.equals(frame.type())) {
                presence.heartbeat(user(session).id());
            }
        } catch (JacksonException ignored) {
            // malformed frames are ignored; heartbeats are fire-and-forget
        }
    }

    /** Deliberately does not mark the user offline: only a missed heartbeat does (absorbs flaky networks). */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.disconnect(user(session).id(), session);
    }

    /** Called when api-service signals that the user's friend list changed. */
    void refreshFriends(long userId) {
        if (hub.isConnected(userId)) {
            Set<Long> friends = loadFriends(userId);
            hub.updateFriends(userId, friends);
            sendSnapshot(userId, friends);
        }
    }

    private void sendSnapshot(long userId, Set<Long> friends) {
        hub.send(userId, new ServerFrames.PresenceSnapshot(presence.statuses(friends)));
    }

    private Set<Long> loadFriends(long userId) {
        Set<String> members = redis.opsForSet().members(RedisKeys.friends(userId));
        return members == null ? Set.of() : members.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    private static AuthUser user(WebSocketSession session) {
        return (AuthUser) session.getAttributes().get(JwtHandshakeInterceptor.USER_ATTRIBUTE);
    }
}
