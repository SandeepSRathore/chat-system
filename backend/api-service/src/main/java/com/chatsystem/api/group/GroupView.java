package com.chatsystem.api.group;

import com.chatsystem.api.user.UserView;
import com.chatsystem.common.ChannelIds;
import java.util.List;

public record GroupView(long id, String channelId, String name, long ownerId, List<UserView> members) {

    public GroupView(long id, String name, long ownerId, List<UserView> members) {
        this(id, ChannelIds.group(id), name, ownerId, members);
    }
}
