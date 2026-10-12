package com.chatsystem.chat.message;

import com.chatsystem.common.wire.ChatMessage;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/** Writes to the key-value message store (Cassandra). */
@Repository
public class MessageStore {

    private final CqlSession session;
    private final PreparedStatement insert;
    private final PreparedStatement maxId;

    public MessageStore(CqlSession session) {
        this.session = session;
        this.insert = session.prepare("""
                INSERT INTO messages_by_channel (channel_id, message_id, sender_id, content, created_at)
                VALUES (?, ?, ?, ?, ?)""");
        this.maxId = session.prepare("SELECT message_id FROM messages_by_channel WHERE channel_id = ? LIMIT 1");
    }

    /** Idempotent: re-delivery of the same Kafka record overwrites the same row. */
    public void save(ChatMessage m) {
        session.execute(insert.bind(m.channelId(), m.messageId(), m.senderId(), m.content(),
                Instant.ofEpochMilli(m.createdAt())));
    }

    public long maxMessageId(String channelId) {
        Row row = session.execute(maxId.bind(channelId)).one();
        return row == null ? 0 : row.getLong("message_id");
    }
}
