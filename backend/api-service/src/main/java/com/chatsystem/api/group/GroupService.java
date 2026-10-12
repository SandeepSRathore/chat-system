package com.chatsystem.api.group;

import com.chatsystem.api.config.ChatProperties;
import com.chatsystem.api.error.ApiException;
import com.chatsystem.api.membership.AfterCommit;
import com.chatsystem.api.membership.MembershipCache;
import com.chatsystem.api.user.UserRepository;
import com.chatsystem.api.user.UserView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private record GroupRow(long id, String name, long ownerId) {
    }

    private record MemberRow(long groupId, long id, String username, String displayName) {
    }

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final MembershipCache membershipCache;
    private final int maxGroupSize;

    public GroupService(JdbcClient jdbc, UserRepository users, MembershipCache membershipCache, ChatProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.membershipCache = membershipCache;
        this.maxGroupSize = props.maxGroupSize();
    }

    @Transactional
    public GroupView create(long ownerId, String name, Collection<Long> memberIds) {
        Set<Long> members = new LinkedHashSet<>();
        members.add(ownerId);
        members.addAll(memberIds);
        checkCapacity(members.size());
        requireUsersExist(members);

        long groupId = jdbc.sql("INSERT INTO chat_groups (name, owner_id) VALUES (:name, :owner) RETURNING id")
                .param("name", name.strip())
                .param("owner", ownerId)
                .query(Long.class)
                .single();
        insertMembers(groupId, members);
        AfterCommit.run(() -> membershipCache.groupMembersAdded(groupId, members));
        return new GroupView(groupId, name.strip(), ownerId, users.findAllById(members));
    }

    @Transactional
    public GroupView addMembers(long actorId, long groupId, Collection<Long> userIds) {
        GroupRow group = requireMembership(actorId, groupId);
        Set<Long> current = memberIds(groupId);
        Set<Long> toAdd = new LinkedHashSet<>(userIds);
        toAdd.removeAll(current);
        checkCapacity(current.size() + toAdd.size());
        requireUsersExist(toAdd);
        insertMembers(groupId, toAdd);
        AfterCommit.run(() -> membershipCache.groupMembersAdded(groupId, toAdd));
        current.addAll(toAdd);
        return new GroupView(groupId, group.name(), group.ownerId(), users.findAllById(current));
    }

    /** The owner can remove anyone but themselves; any member can remove (leave) themselves. */
    @Transactional
    public void removeMember(long actorId, long groupId, long userId) {
        GroupRow group = requireMembership(actorId, groupId);
        if (actorId != group.ownerId() && actorId != userId) {
            throw ApiException.forbidden("Only the group owner can remove other members");
        }
        if (userId == group.ownerId()) {
            throw ApiException.badRequest("The owner cannot leave the group");
        }
        jdbc.sql("DELETE FROM group_members WHERE group_id = :g AND user_id = :u")
                .param("g", groupId)
                .param("u", userId)
                .update();
        AfterCommit.run(() -> membershipCache.groupMemberRemoved(groupId, userId));
    }

    public GroupView get(long actorId, long groupId) {
        GroupRow group = requireMembership(actorId, groupId);
        return new GroupView(group.id(), group.name(), group.ownerId(), users.findAllById(memberIds(groupId)));
    }

    public List<GroupView> groupsOf(long userId) {
        List<GroupRow> groups = jdbc.sql("""
                        SELECT g.id, g.name, g.owner_id FROM chat_groups g
                        JOIN group_members m ON m.group_id = g.id
                        WHERE m.user_id = :userId
                        ORDER BY g.name""")
                .param("userId", userId)
                .query(GroupRow.class)
                .list();
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, List<UserView>> membersByGroup = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT m.group_id, u.id, u.username, u.display_name FROM group_members m
                        JOIN users u ON u.id = m.user_id
                        WHERE m.group_id IN (:ids)
                        ORDER BY u.display_name""")
                .param("ids", groups.stream().map(GroupRow::id).toList())
                .query(MemberRow.class)
                .list()
                .forEach(r -> membersByGroup.computeIfAbsent(r.groupId(), k -> new ArrayList<>())
                        .add(new UserView(r.id(), r.username(), r.displayName())));
        return groups.stream()
                .map(g -> new GroupView(g.id(), g.name(), g.ownerId(), membersByGroup.getOrDefault(g.id(), List.of())))
                .toList();
    }

    public boolean isMember(long groupId, long userId) {
        return jdbc.sql("SELECT count(*) FROM group_members WHERE group_id = :g AND user_id = :u")
                .param("g", groupId)
                .param("u", userId)
                .query(Long.class)
                .single() > 0;
    }

    private GroupRow requireMembership(long userId, long groupId) {
        GroupRow group = jdbc.sql("SELECT id, name, owner_id FROM chat_groups WHERE id = :id")
                .param("id", groupId)
                .query(GroupRow.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Group not found"));
        if (!isMember(groupId, userId)) {
            throw ApiException.forbidden("You are not a member of this group");
        }
        return group;
    }

    private Set<Long> memberIds(long groupId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT user_id FROM group_members WHERE group_id = :g")
                .param("g", groupId)
                .query(Long.class)
                .list());
    }

    private void insertMembers(long groupId, Collection<Long> userIds) {
        for (Long userId : userIds) {
            jdbc.sql("INSERT INTO group_members (group_id, user_id) VALUES (:g, :u) ON CONFLICT DO NOTHING")
                    .param("g", groupId)
                    .param("u", userId)
                    .update();
        }
    }

    private void checkCapacity(int size) {
        if (size > maxGroupSize) {
            throw ApiException.badRequest("A group can have at most " + maxGroupSize + " members");
        }
    }

    private void requireUsersExist(Collection<Long> userIds) {
        if (users.findAllById(userIds).size() != userIds.size()) {
            throw ApiException.notFound("One or more users do not exist");
        }
    }
}
