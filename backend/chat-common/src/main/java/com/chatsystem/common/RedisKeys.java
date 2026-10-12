package com.chatsystem.common;

/** Every Redis key and pub/sub channel shared between services. See docs/DESIGN.md §4. */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ---- membership (written by api-service) ----
    public static String channelMembers(String channelId) {
        return "channel:members:" + channelId;
    }

    public static String friends(long userId) {
        return "friends:" + userId;
    }

    // ---- chat servers ----
    public static String session(long userId) {
        return "session:" + userId;
    }

    public static String serverConnections(String serverId) {
        return "chat:server:conns:" + serverId;
    }

    public static String channelSequence(String channelId) {
        return "channel:seq:" + channelId;
    }

    /** Idempotency record for a client's retried send, holding the already-accepted message. */
    public static String clientMessage(long userId, String clientMsgId) {
        return "client-msg:" + userId + ":" + clientMsgId;
    }

    // ---- presence ----
    public static final String PRESENCE_PREFIX = "presence:";

    public static String presence(long userId) {
        return PRESENCE_PREFIX + userId;
    }

    public static String lastActive(long userId) {
        return "presence:last_active:" + userId;
    }

    // ---- pub/sub channels ----
    public static String deliverChannel(String serverId) {
        return "chat.deliver." + serverId;
    }

    public static final String PRESENCE_EVENTS_PATTERN = "presence.user.*";

    public static String presenceEvents(long userId) {
        return "presence.user." + userId;
    }

    public static final String FRIENDS_CHANGED_PATTERN = "friends.changed.*";

    public static String friendsChanged(long userId) {
        return "friends.changed." + userId;
    }

    public static final String KEY_EXPIRED_CHANNEL = "__keyevent@0__:expired";

    /** Returns the trailing numeric id of keys/channels such as {@code presence.user.42}. */
    public static long trailingId(String keyOrChannel) {
        int idx = Math.max(keyOrChannel.lastIndexOf('.'), keyOrChannel.lastIndexOf(':'));
        return Long.parseLong(keyOrChannel.substring(idx + 1));
    }
}
