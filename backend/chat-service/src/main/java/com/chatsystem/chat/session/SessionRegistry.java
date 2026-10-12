package com.chatsystem.chat.session;

import com.chatsystem.chat.config.ChatServerProperties;
import com.chatsystem.common.RedisKeys;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WebSocket connections held by this chat server, plus the cluster-wide
 * {@code session:{userId} -> serverId} directory the router uses to find them.
 * One active connection per user: the newest one wins.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    static final Duration SESSION_TTL = Duration.ofSeconds(60);
    private static final Duration CONNECTIONS_TTL = Duration.ofSeconds(30);
    private static final CloseStatus REPLACED = new CloseStatus(4001, "Replaced by a newer connection");

    /** Refresh our directory entry only if it still points at us (or has expired). */
    private static final RedisScript<Long> REFRESH_IF_OWNED = RedisScript.of("""
            local v = redis.call('GET', KEYS[1])
            if (not v) or v == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
              return 1
            end
            return 0""", Long.class);

    private static final RedisScript<Long> DELETE_IF_OWNED = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0""", Long.class);

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String serverId;

    public SessionRegistry(StringRedisTemplate redis, JsonMapper json, ChatServerProperties props) {
        this.redis = redis;
        this.json = json;
        this.serverId = props.serverId();
    }

    /** Returns a thread-safe view of the session; always send through it. */
    public WebSocketSession register(long userId, WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024);
        WebSocketSession previous = sessions.put(userId, session);
        if (previous != null) {
            closeQuietly(previous, REPLACED);
        }
        redis.opsForValue().set(RedisKeys.session(userId), serverId, SESSION_TTL);
        reportConnections();
        return session;
    }

    public void unregister(long userId, WebSocketSession session) {
        boolean removed = sessions.computeIfPresent(userId,
                (id, current) -> current.getId().equals(session.getId()) ? null : current) == null;
        if (removed) {
            redis.execute(DELETE_IF_OWNED, List.of(RedisKeys.session(userId)), serverId);
        }
        reportConnections();
    }

    /** Pushes a frame to the user if they are connected here; returns false otherwise. */
    public boolean sendToUser(long userId, Object frame) {
        WebSocketSession session = sessions.get(userId);
        return session != null && send(session, frame);
    }

    public boolean send(WebSocketSession session, Object frame) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send to session {}: {}", session.getId(), e.getMessage());
            return false;
        }
    }

    public int size() {
        return sessions.size();
    }

    /**
     * Keeps directory entries alive. If another server now owns a user's entry, the user
     * reconnected elsewhere, so the stale local socket is closed (newest wins across servers).
     */
    @Scheduled(fixedRate = 20_000)
    void refreshDirectory() {
        String ttl = String.valueOf(SESSION_TTL.toSeconds());
        sessions.forEach((userId, session) -> {
            Long owned = redis.execute(REFRESH_IF_OWNED, List.of(RedisKeys.session(userId)), serverId, ttl);
            if (owned != null && owned == 0) {
                sessions.remove(userId, session);
                closeQuietly(session, REPLACED);
            }
        });
    }

    /** Load metric read by api-service's discovery to pick the least-loaded server. */
    @Scheduled(fixedRate = 5_000)
    void reportConnections() {
        redis.opsForValue().set(RedisKeys.serverConnections(serverId), String.valueOf(sessions.size()), CONNECTIONS_TTL);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // already gone
        }
    }
}
