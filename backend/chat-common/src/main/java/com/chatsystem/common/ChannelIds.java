package com.chatsystem.common;

import java.util.List;

/**
 * A channel is either a 1:1 conversation or a group. Both share one id space so that the
 * message table, the id generator and the router only deal with "channels".
 *
 * <ul>
 *   <li>{@code dm:{minUserId}:{maxUserId}} for a 1:1 conversation</li>
 *   <li>{@code g:{groupId}} for a group</li>
 * </ul>
 */
public final class ChannelIds {

    private static final String DM_PREFIX = "dm:";
    private static final String GROUP_PREFIX = "g:";

    private ChannelIds() {
    }

    public static String direct(long userA, long userB) {
        if (userA == userB) {
            throw new IllegalArgumentException("A direct channel needs two different users");
        }
        return DM_PREFIX + Math.min(userA, userB) + ":" + Math.max(userA, userB);
    }

    public static String group(long groupId) {
        return GROUP_PREFIX + groupId;
    }

    public static boolean isDirect(String channelId) {
        return channelId != null && channelId.startsWith(DM_PREFIX);
    }

    public static boolean isGroup(String channelId) {
        return channelId != null && channelId.startsWith(GROUP_PREFIX);
    }

    /** Both participants of a direct channel. */
    public static List<Long> directMembers(String channelId) {
        if (!isDirect(channelId)) {
            throw new IllegalArgumentException("Not a direct channel: " + channelId);
        }
        String[] parts = channelId.substring(DM_PREFIX.length()).split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed direct channel: " + channelId);
        }
        return List.of(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    public static long groupId(String channelId) {
        if (!isGroup(channelId)) {
            throw new IllegalArgumentException("Not a group channel: " + channelId);
        }
        return Long.parseLong(channelId.substring(GROUP_PREFIX.length()));
    }
}
