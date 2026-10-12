package com.chatsystem.common.wire;

/** {@code lastActiveAt} is epoch millis of the last heartbeat, or 0 if the user was never seen. */
public record PresenceStatus(long userId, boolean online, long lastActiveAt) {
}
