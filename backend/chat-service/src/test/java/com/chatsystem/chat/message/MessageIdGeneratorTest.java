package com.chatsystem.chat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class MessageIdGeneratorTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MessageStore store = mock(MessageStore.class);
    private final MessageIdGenerator generator = new MessageIdGenerator(redis, store);

    @Test
    void existingCounterIsIncrementedWithoutTouchingCassandra() {
        // Includes a brand-new channel: api-service created its counter at 0.
        when(redis.execute(same(MessageIdGenerator.INCR_IF_EXISTS), eq(List.of("channel:seq:dm:1:2")))).thenReturn(1L);
        assertThat(generator.next("dm:1:2")).isEqualTo(1);
        verify(store, never()).maxMessageId("dm:1:2");
    }

    @Test
    void missingCounterIsSeededAfterThePersistedMaximum() {
        when(redis.execute(same(MessageIdGenerator.INCR_IF_EXISTS), eq(List.of("channel:seq:g:5")))).thenReturn(null);
        when(store.maxMessageId("g:5")).thenReturn(500L);
        when(redis.execute(same(MessageIdGenerator.SEED_AND_INCR), eq(List.of("channel:seq:g:5")), eq("500")))
                .thenReturn(501L);
        assertThat(generator.next("g:5")).isEqualTo(501);
    }

    @Test
    void missingCounterOfAnEmptyChannelStartsAtOne() {
        when(redis.execute(same(MessageIdGenerator.INCR_IF_EXISTS), eq(List.of("channel:seq:g:6")))).thenReturn(null);
        when(store.maxMessageId("g:6")).thenReturn(0L);
        when(redis.execute(same(MessageIdGenerator.SEED_AND_INCR), eq(List.of("channel:seq:g:6")), eq("0")))
                .thenReturn(1L);
        assertThat(generator.next("g:6")).isEqualTo(1);
    }
}
