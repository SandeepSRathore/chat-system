package com.chatsystem.api.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    record Credentials(long id, String username, String displayName, String passwordHash) {
        UserView view() {
            return new UserView(id, username, displayName);
        }
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws org.springframework.dao.DuplicateKeyException if the username is taken */
    public UserView insert(String username, String displayName, String passwordHash) {
        long id = jdbc.sql("""
                        INSERT INTO users (username, display_name, password_hash)
                        VALUES (:username, :displayName, :passwordHash)
                        RETURNING id""")
                .param("username", username)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .query(Long.class)
                .single();
        return new UserView(id, username, displayName);
    }

    Optional<Credentials> findCredentials(String username) {
        return jdbc.sql("SELECT id, username, display_name, password_hash FROM users WHERE username = :username")
                .param("username", username)
                .query(Credentials.class)
                .optional();
    }

    public Optional<UserView> findById(long id) {
        return jdbc.sql("SELECT id, username, display_name FROM users WHERE id = :id")
                .param("id", id)
                .query(UserView.class)
                .optional();
    }

    public List<UserView> findAllById(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, username, display_name FROM users WHERE id IN (:ids) ORDER BY display_name")
                .param("ids", ids)
                .query(UserView.class)
                .list();
    }

    public List<UserView> search(String query, long excludeUserId, int limit) {
        return jdbc.sql("""
                        SELECT id, username, display_name FROM users
                        WHERE id <> :exclude
                          AND (username LIKE :prefix OR lower(display_name) LIKE :prefix)
                        ORDER BY username
                        LIMIT :limit""")
                .param("exclude", excludeUserId)
                .param("prefix", escapeLike(query.toLowerCase()) + "%")
                .param("limit", limit)
                .query(UserView.class)
                .list();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
