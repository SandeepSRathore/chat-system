package com.chatsystem.chat.message;

import com.chatsystem.common.Topics;
import com.chatsystem.common.wire.ChatMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumer group #1 on the sync queue: writes every message to the key-value store. */
@Component
class MessagePersister {

    private final MessageStore store;
    private final JsonMapper json;

    MessagePersister(MessageStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(topics = Topics.MESSAGE_SYNC, groupId = "message-persister")
    void persist(String payload) {
        store.save(json.readValue(payload, ChatMessage.class));
    }
}
