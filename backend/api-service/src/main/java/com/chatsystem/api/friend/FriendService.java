package com.chatsystem.api.friend;

import com.chatsystem.api.error.ApiException;
import com.chatsystem.api.membership.AfterCommit;
import com.chatsystem.api.membership.MembershipCache;
import com.chatsystem.api.user.UserRepository;
import com.chatsystem.api.user.UserView;
import com.chatsystem.common.ChannelIds;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Friendship needs consent: a request stays pending until the other user accepts it. Only then
 * can the two message each other and see each other's presence. Friendships are stored as two rows.
 */
@Service
public class FriendService {

    public enum RequestStatus { PENDING, ACCEPTED }

    public record RequestResult(RequestStatus status, UserView user) {
    }

    public record FriendRequests(List<UserView> incoming, List<UserView> outgoing) {
    }

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final MembershipCache membershipCache;

    public FriendService(JdbcClient jdbc, UserRepository users, MembershipCache membershipCache) {
        this.jdbc = jdbc;
        this.users = users;
        this.membershipCache = membershipCache;
    }

    /** Sends a request; if the other user already asked us, that counts as mutual consent. */
    @Transactional
    public RequestResult sendRequest(long userId, long otherId) {
        if (userId == otherId) {
            throw ApiException.badRequest("You cannot befriend yourself");
        }
        UserView other = users.findById(otherId).orElseThrow(() -> ApiException.notFound("User not found"));
        if (areFriends(userId, otherId)) {
            return new RequestResult(RequestStatus.ACCEPTED, other);
        }
        if (deleteRequest(otherId, userId) > 0) {
            befriend(userId, otherId);
            return new RequestResult(RequestStatus.ACCEPTED, other);
        }
        jdbc.sql("""
                        INSERT INTO friend_requests (requester_id, addressee_id) VALUES (:from, :to)
                        ON CONFLICT DO NOTHING""")
                .param("from", userId)
                .param("to", otherId)
                .update();
        AfterCommit.run(() -> membershipCache.friendRequestsChanged(otherId));
        return new RequestResult(RequestStatus.PENDING, other);
    }

    @Transactional
    public UserView accept(long userId, long requesterId) {
        UserView requester = users.findById(requesterId).orElseThrow(() -> ApiException.notFound("User not found"));
        if (deleteRequest(requesterId, userId) == 0) {
            throw ApiException.notFound("No pending friend request from this user");
        }
        befriend(userId, requesterId);
        return requester;
    }

    /** Declines an incoming request or withdraws an outgoing one. */
    @Transactional
    public void decline(long userId, long otherId) {
        if (deleteRequest(otherId, userId) + deleteRequest(userId, otherId) == 0) {
            throw ApiException.notFound("No pending friend request with this user");
        }
        AfterCommit.run(() -> membershipCache.friendRequestsChanged(userId, otherId));
    }

    public FriendRequests requestsOf(long userId) {
        List<UserView> incoming = jdbc.sql("""
                        SELECT u.id, u.username, u.display_name FROM friend_requests r
                        JOIN users u ON u.id = r.requester_id
                        WHERE r.addressee_id = :userId
                        ORDER BY r.created_at""")
                .param("userId", userId)
                .query(UserView.class)
                .list();
        List<UserView> outgoing = jdbc.sql("""
                        SELECT u.id, u.username, u.display_name FROM friend_requests r
                        JOIN users u ON u.id = r.addressee_id
                        WHERE r.requester_id = :userId
                        ORDER BY r.created_at""")
                .param("userId", userId)
                .query(UserView.class)
                .list();
        return new FriendRequests(incoming, outgoing);
    }

    public List<UserView> friendsOf(long userId) {
        return jdbc.sql("""
                        SELECT u.id, u.username, u.display_name FROM friendships f
                        JOIN users u ON u.id = f.friend_id
                        WHERE f.user_id = :userId
                        ORDER BY u.display_name""")
                .param("userId", userId)
                .query(UserView.class)
                .list();
    }

    public boolean areFriends(long userA, long userB) {
        return jdbc.sql("SELECT count(*) FROM friendships WHERE user_id = :a AND friend_id = :b")
                .param("a", userA)
                .param("b", userB)
                .query(Long.class)
                .single() > 0;
    }

    private void befriend(long userA, long userB) {
        int inserted = jdbc.sql("""
                        INSERT INTO friendships (user_id, friend_id) VALUES (:a, :b), (:b, :a)
                        ON CONFLICT DO NOTHING""")
                .param("a", userA)
                .param("b", userB)
                .update();
        // Crossing requests sent at the same moment can leave the opposite one pending.
        deleteRequest(userA, userB);
        deleteRequest(userB, userA);
        // Only a brand-new friendship has a brand-new channel. Starting the counter at 0 for an existing
        // one (e.g. after a Redis flush) would reuse message ids and overwrite stored messages.
        boolean newChannel = inserted > 0;
        AfterCommit.run(() -> {
            if (newChannel) {
                membershipCache.channelCreated(ChannelIds.direct(userA, userB));
            }
            membershipCache.friendshipAdded(userA, userB);
        });
    }

    private int deleteRequest(long requesterId, long addresseeId) {
        return jdbc.sql("DELETE FROM friend_requests WHERE requester_id = :from AND addressee_id = :to")
                .param("from", requesterId)
                .param("to", addresseeId)
                .update();
    }
}
