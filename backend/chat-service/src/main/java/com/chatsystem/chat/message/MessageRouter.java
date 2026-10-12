package com.chatsystem.chat.message;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.Topics;
import com.chatsystem.common.wire.ChatMessage;
import com.chatsystem.common.wire.DeliveryEnvelope;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer group #2 on the sync queue: fans a message out to every other channel member.
 * Online members are forwarded to the chat server that holds their socket; offline members
 * will read it from history (this is where the book's push notification would be sent).
 */
@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public MessageRouter(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @KafkaListener(topics = Topics.MESSAGE_SYNC, groupId = "message-router")
    void onMessage(String payload) {
        route(json.readValue(payload, ChatMessage.class));
    }

    public void route(ChatMessage message) {
        Set<String> members = redis.opsForSet().members(RedisKeys.channelMembers(message.channelId()));
        if (members == null || members.isEmpty()) {
            return;
        }
        List<Long> recipients = members.stream()
                .map(Long::parseLong)
                .filter(id -> id != message.senderId())
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        List<String> servers = redis.opsForValue().multiGet(recipients.stream().map(RedisKeys::session).toList());
        for (int i = 0; i < recipients.size(); i++) {
            String serverId = servers == null ? null : servers.get(i);
            long recipient = recipients.get(i);
            if (serverId == null) {
                log.debug("User {} offline; message {}/{} waits in history (push-notification hook)",
                        recipient, message.channelId(), message.messageId());
                continue;
            }
            redis.convertAndSend(RedisKeys.deliverChannel(serverId),
                    json.writeValueAsString(new DeliveryEnvelope(recipient, message)));
        }
    }
}
