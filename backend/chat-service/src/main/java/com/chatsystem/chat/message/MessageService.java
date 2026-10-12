package com.chatsystem.chat.message;

import com.chatsystem.chat.config.ChatServerProperties;
import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.Topics;
import com.chatsystem.common.wire.ChatMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps 1–3 of the send flow: authorize, assign an id, append to the message sync queue.
 * Persistence and delivery happen asynchronously in {@link MessagePersister} and {@link MessageRouter}.
 */
@Service
public class MessageService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);
    private static final String IN_FLIGHT = "IN_FLIGHT";

    private final StringRedisTemplate redis;
    private final MessageIdGenerator idGenerator;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Clock clock;
    private final int maxLength;

    @Autowired
    public MessageService(StringRedisTemplate redis, MessageIdGenerator idGenerator,
                          KafkaTemplate<String, String> kafka, JsonMapper json, ChatServerProperties props) {
        this(redis, idGenerator, kafka, json, props, Clock.systemUTC());
    }

    MessageService(StringRedisTemplate redis, MessageIdGenerator idGenerator, KafkaTemplate<String, String> kafka,
                   JsonMapper json, ChatServerProperties props, Clock clock) {
        this.redis = redis;
        this.idGenerator = idGenerator;
        this.kafka = kafka;
        this.json = json;
        this.clock = clock;
        this.maxLength = props.maxMessageLength();
    }

    /**
     * Completes once the message is safely in Kafka, which is when the author gets its ACK.
     * Completes with {@code null} for a duplicate of a send that is still in flight.
     */
    public CompletableFuture<ChatMessage> send(long senderId, String clientMsgId, String channelId, String content) {
        if (clientMsgId == null || clientMsgId.isBlank() || clientMsgId.length() > 64) {
            throw new MessageRejectedException("clientMsgId is required (max 64 chars)");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new MessageRejectedException("channelId is required");
        }
        if (content == null || content.isBlank()) {
            throw new MessageRejectedException("Message is empty");
        }
        if (content.length() > maxLength) {
            throw new MessageRejectedException("Message exceeds " + maxLength + " characters");
        }
        if (!Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeys.channelMembers(channelId), String.valueOf(senderId)))) {
            throw new MessageRejectedException("You are not a member of this channel");
        }

        // A client re-sending after a reconnect gets the original message back instead of a duplicate.
        // The key is reserved before producing so a retry racing the first attempt is dropped too.
        String idempotencyKey = RedisKeys.clientMessage(senderId, clientMsgId);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(idempotencyKey, IN_FLIGHT, IDEMPOTENCY_TTL))) {
            String accepted = redis.opsForValue().get(idempotencyKey);
            if (accepted == null || IN_FLIGHT.equals(accepted)) {
                // The first attempt is still in flight; its ACK will answer this retry.
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(json.readValue(accepted, ChatMessage.class));
        }

        try {
            ChatMessage message = new ChatMessage(channelId, idGenerator.next(channelId), senderId, content,
                    clock.millis());
            String payload = json.writeValueAsString(message);
            return kafka.send(Topics.MESSAGE_SYNC, channelId, payload)
                    .whenComplete((result, error) -> {
                        if (error == null) {
                            redis.opsForValue().set(idempotencyKey, payload, IDEMPOTENCY_TTL);
                        } else {
                            redis.delete(idempotencyKey);
                        }
                    })
                    .thenApply(result -> message);
        } catch (RuntimeException e) {
            redis.delete(idempotencyKey);
            throw e;
        }
    }
}
