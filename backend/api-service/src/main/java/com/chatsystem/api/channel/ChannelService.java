package com.chatsystem.api.channel;

import com.chatsystem.api.error.ApiException;
import com.chatsystem.api.friend.FriendService;
import com.chatsystem.api.group.GroupService;
import com.chatsystem.api.user.UserRepository;
import com.chatsystem.api.user.UserView;
import com.chatsystem.common.ChannelIds;
import com.chatsystem.common.wire.ChatMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

@Service
public class ChannelService {

    private static final int MAX_PAGE = 100;

    private final FriendService friendService;
    private final GroupService groupService;
    private final UserRepository users;
    private final MessageHistoryRepository history;

    public ChannelService(FriendService friendService, GroupService groupService, UserRepository users,
                          MessageHistoryRepository history) {
        this.friendService = friendService;
        this.groupService = groupService;
        this.users = users;
        this.history = history;
    }

    /** Every DM (one per friend) and group of the user, most recently active first. */
    public List<ChannelView> channelsOf(long userId) {
        UserView me = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        List<ChannelView> channels = new ArrayList<>();
        for (UserView friend : friendService.friendsOf(userId)) {
            channels.add(new ChannelView(ChannelIds.direct(userId, friend.id()), ChannelView.Type.DIRECT,
                    friend.displayName(), friend.id(), null, null, List.of(me, friend), null));
        }
        groupService.groupsOf(userId).forEach(g -> channels.add(new ChannelView(g.channelId(), ChannelView.Type.GROUP,
                g.name(), null, g.id(), g.ownerId(), g.members(), null)));

        // Fetch each channel's last message from Cassandra concurrently.
        List<CompletableFuture<ChannelView>> withLast = channels.stream()
                .map(c -> history.latestAsync(c.id()).toCompletableFuture()
                        .thenApply(last -> c.withLastMessage(last.orElse(null))))
                .toList();
        return withLast.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingLong(ChannelService::lastActivity).reversed()
                        .thenComparing(ChannelView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<ChatMessage> history(long userId, String channelId, Long beforeMessageId, int limit) {
        requireAccess(userId, channelId);
        return history.page(channelId, beforeMessageId, Math.clamp(limit, 1, MAX_PAGE));
    }

    private void requireAccess(long userId, String channelId) {
        boolean allowed;
        try {
            if (ChannelIds.isDirect(channelId)) {
                List<Long> members = ChannelIds.directMembers(channelId);
                allowed = members.contains(userId) && friendService.areFriends(members.get(0), members.get(1));
            } else if (ChannelIds.isGroup(channelId)) {
                allowed = groupService.isMember(ChannelIds.groupId(channelId), userId);
            } else {
                throw ApiException.badRequest("Unknown channel type");
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Malformed channel id");
        }
        if (!allowed) {
            throw ApiException.forbidden("You are not a member of this channel");
        }
    }

    private static long lastActivity(ChannelView c) {
        return c.lastMessage() == null ? 0 : c.lastMessage().createdAt();
    }
}
