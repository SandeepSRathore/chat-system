package com.chatsystem.api.channel;

import com.chatsystem.api.user.UserView;
import com.chatsystem.common.wire.ChatMessage;
import java.util.List;

/**
 * A conversation in the sidebar. For {@code DIRECT} channels {@code name} is the other person's
 * display name and {@code peerId} their id; for {@code GROUP} channels {@code groupId}/{@code ownerId} are set.
 */
public record ChannelView(
        String id,
        Type type,
        String name,
        Long peerId,
        Long groupId,
        Long ownerId,
        List<UserView> members,
        ChatMessage lastMessage) {

    public enum Type { DIRECT, GROUP }

    ChannelView withLastMessage(ChatMessage message) {
        return new ChannelView(id, type, name, peerId, groupId, ownerId, members, message);
    }
}
