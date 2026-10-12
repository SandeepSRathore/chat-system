package com.chatsystem.presence;

import com.chatsystem.common.wire.PresenceStatus;
import com.chatsystem.common.wire.ServerFrames;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The presence sockets held by this instance and a watcher index
 * ({@code watchedUserId -> local users who are their friends}). Each instance subscribes to
 * presence events once and uses this index to fan them out locally.
 */
@Component
public class PresenceHub {

    private static final Logger log = LoggerFactory.getLogger(PresenceHub.class);

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> friendsOf = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> watchers = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public PresenceHub(JsonMapper json) {
        this.json = json;
    }

    public synchronized WebSocketSession connect(long userId, WebSocketSession raw, Set<Long> friends) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
        WebSocketSession previous = sessions.put(userId, session);
        if (previous != null) {
            try {
                previous.close(new CloseStatus(4001, "Replaced by a newer connection"));
            } catch (IOException ignored) {
                // already gone
            }
        }
        updateFriends(userId, friends);
        return session;
    }

    public synchronized void disconnect(long userId, WebSocketSession raw) {
        WebSocketSession current = sessions.get(userId);
        if (current == null || !current.getId().equals(raw.getId())) {
            return;
        }
        sessions.remove(userId);
        updateFriends(userId, Set.of());
        friendsOf.remove(userId);
    }

    public boolean isConnected(long userId) {
        return sessions.containsKey(userId);
    }

    public synchronized void updateFriends(long userId, Set<Long> friends) {
        Set<Long> previous = friendsOf.getOrDefault(userId, Set.of());
        for (Long old : previous) {
            if (!friends.contains(old)) {
                watchers.computeIfPresent(old, (k, set) -> {
                    set.remove(userId);
                    return set.isEmpty() ? null : set;
                });
            }
        }
        for (Long friend : friends) {
            watchers.computeIfAbsent(friend, k -> ConcurrentHashMap.newKeySet()).add(userId);
        }
        friendsOf.put(userId, Set.copyOf(friends));
    }

    /** Pushes a friend's status change to every local user watching them. */
    public void notifyWatchers(PresenceStatus status) {
        for (Long watcher : watchers.getOrDefault(status.userId(), Set.of())) {
            send(watcher, new ServerFrames.Presence(status));
        }
    }

    public void send(long userId, Object frame) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to push presence to {}: {}", userId, e.getMessage());
        }
    }

    Set<Long> watchersOf(long userId) {
        return Set.copyOf(watchers.getOrDefault(userId, Set.of()));
    }
}
