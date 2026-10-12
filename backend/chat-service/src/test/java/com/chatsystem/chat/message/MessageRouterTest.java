package com.chatsystem.chat.message;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsystem.common.wire.ChatMessage;
import com.chatsystem.common.wire.DeliveryEnvelope;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class MessageRouterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final MessageRouter router = new MessageRouter(redis, json);

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void forwardsToTheServerOfEachOnlineRecipientAndSkipsSenderAndOfflineUsers() {
        ChatMessage message = new ChatMessage("g:1", 7, 1, "hello", 1000);
        when(sets.members("channel:members:g:1")).thenReturn(Set.of("1", "2", "3"));
        // Set iteration order is unspecified, so answer per key.
        when(values.multiGet(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(0);
            return keys.stream().map(k -> k.equals("session:2") ? "chat-2" : null).toList();
        });

        router.route(message);

        verify(redis).convertAndSend("chat.deliver.chat-2", json.writeValueAsString(new DeliveryEnvelope(2, message)));
        verify(redis, never()).convertAndSend(org.mockito.ArgumentMatchers.eq("chat.deliver.null"), anyString());
    }

    @Test
    void directMessageToOfflineUserPublishesNothing() {
        when(sets.members("channel:members:dm:1:2")).thenReturn(Set.of("1", "2"));
        when(values.multiGet(List.of("session:2"))).thenReturn(Arrays.asList((String) null));

        router.route(new ChatMessage("dm:1:2", 1, 1, "hi", 1000));

        verify(redis, never()).convertAndSend(anyString(), anyString());
    }
}
