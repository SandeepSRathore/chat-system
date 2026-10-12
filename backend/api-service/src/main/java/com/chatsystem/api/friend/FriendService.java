package com.chatsystem.api.friend;

import com.chatsystem.api.error.ApiException;
import com.chatsystem.api.membership.AfterCommit;
import com.chatsystem.api.membership.MembershipCache;
import com.chatsystem.api.user.UserRepository;
import com.chatsystem.api.user.UserView;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Friendship is mutual and immediate (no request/accept step), stored as two rows. */
@Service
public class FriendService {

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final MembershipCache membershipCache;

    public FriendService(JdbcClient jdbc, UserRepository users, MembershipCache membershipCache) {
        this.jdbc = jdbc;
        this.users = users;
        this.membershipCache = membershipCache;
    }

    @Transactional
    public UserView addFriend(long userId, long friendId) {
        if (userId == friendId) {
            throw ApiException.badRequest("You cannot befriend yourself");
        }
        UserView friend = users.findById(friendId).orElseThrow(() -> ApiException.notFound("User not found"));
        jdbc.sql("""
                        INSERT INTO friendships (user_id, friend_id) VALUES (:a, :b), (:b, :a)
                        ON CONFLICT DO NOTHING""")
                .param("a", userId)
                .param("b", friendId)
                .update();
        AfterCommit.run(() -> membershipCache.friendshipAdded(userId, friendId));
        return friend;
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
}
