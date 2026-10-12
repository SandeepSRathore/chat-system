package com.chatsystem.api.channel;

import com.chatsystem.common.wire.ChatMessage;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Repository;

/** Reads the key-value message store. Partition = channel, clustering = message_id DESC. */
@Repository
public class MessageHistoryRepository {

    private static final String COLUMNS = "channel_id, message_id, sender_id, content, created_at";

    private final CqlSession session;
    private final PreparedStatement latest;
    private final PreparedStatement before;

    public MessageHistoryRepository(CqlSession session) {
        this.session = session;
        this.latest = session.prepare("SELECT " + COLUMNS + " FROM messages_by_channel WHERE channel_id = ? LIMIT ?");
        this.before = session.prepare(
                "SELECT " + COLUMNS + " FROM messages_by_channel WHERE channel_id = ? AND message_id < ? LIMIT ?");
    }

    /** A page of history in chronological order, ending just before {@code beforeMessageId} (or at the newest). */
    public List<ChatMessage> page(String channelId, Long beforeMessageId, int limit) {
        var statement = beforeMessageId == null
                ? latest.bind(channelId, limit)
                : before.bind(channelId, beforeMessageId, limit);
        List<ChatMessage> newestFirst = new ArrayList<>();
        session.execute(statement).forEach(row -> newestFirst.add(map(row)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    public CompletionStage<Optional<ChatMessage>> latestAsync(String channelId) {
        return session.executeAsync(latest.bind(channelId, 1))
                .thenApply(rs -> Optional.ofNullable(rs.one()).map(MessageHistoryRepository::map));
    }

    private static ChatMessage map(Row row) {
        return new ChatMessage(
                row.getString("channel_id"),
                row.getLong("message_id"),
                row.getLong("sender_id"),
                row.getString("content"),
                row.getInstant("created_at").toEpochMilli());
    }
}
