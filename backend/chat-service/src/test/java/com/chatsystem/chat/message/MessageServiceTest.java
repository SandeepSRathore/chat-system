package com.chatsystem.chat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsystem.chat.config.ChatServerProperties;
import com.chatsystem.common.wire.ChatMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

class MessageServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MessageIdGenerator ids = mock(MessageIdGenerator.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final MessageService service = new MessageService(redis, ids, kafka, json,
            new ChatServerProperties("chat-1", "ws://x", "zk", 10, null),
            Clock.fixed(Instant.ofEpochMilli(5000), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void acceptedMessageIsProducedKeyedByChannel() {
        when(sets.isMember("channel:members:dm:1:2", "1")).thenReturn(true);
        when(values.setIfAbsent(eq("client-msg:1:c1"), anyString(), any(Duration.class))).thenReturn(true);
        when(ids.next("dm:1:2")).thenReturn(9L);
        when(kafka.send(eq("message.sync"), eq("dm:1:2"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        ChatMessage sent = service.send(1, "c1", "dm:1:2", "hello").join();

        assertThat(sent).isEqualTo(new ChatMessage("dm:1:2", 9, 1, "hello", 5000));
        verify(values).set(eq("client-msg:1:c1"), eq(json.writeValueAsString(sent)), any(Duration.class));
    }

    @Test
    void nonMembersAreRejectedBeforeAnIdIsAllocated() {
        when(sets.isMember("channel:members:g:3", "1")).thenReturn(false);
        assertThatThrownBy(() -> service.send(1, "c1", "g:3", "hi"))
                .isInstanceOf(MessageRejectedException.class)
                .hasMessageContaining("not a member");
        verify(ids, never()).next(anyString());
    }

    @Test
    void overlongAndEmptyMessagesAreRejected() {
        assertThatThrownBy(() -> service.send(1, "c1", "g:3", "12345678901")).isInstanceOf(MessageRejectedException.class);
        assertThatThrownBy(() -> service.send(1, "c1", "g:3", "  ")).isInstanceOf(MessageRejectedException.class);
    }

    @Test
    void retriedSendReturnsTheOriginalMessageWithoutProducingAgain() {
        ChatMessage original = new ChatMessage("dm:1:2", 4, 1, "hello", 1000);
        when(sets.isMember("channel:members:dm:1:2", "1")).thenReturn(true);
        when(values.setIfAbsent(eq("client-msg:1:c1"), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get("client-msg:1:c1")).thenReturn(json.writeValueAsString(original));

        assertThat(service.send(1, "c1", "dm:1:2", "hello").join()).isEqualTo(original);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void failedProduceReleasesTheIdempotencyKey() {
        when(sets.isMember("channel:members:dm:1:2", "1")).thenReturn(true);
        when(values.setIfAbsent(eq("client-msg:1:c1"), anyString(), any(Duration.class))).thenReturn(true);
        when(ids.next("dm:1:2")).thenReturn(1L);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThat(service.send(1, "c1", "dm:1:2", "hello")).isCompletedExceptionally();
        verify(redis).delete("client-msg:1:c1");
    }
}
