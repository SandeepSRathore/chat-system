package com.chatsystem.chat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MessageIdGeneratorTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MessageStore store = mock(MessageStore.class);
    private final MessageIdGenerator generator = new MessageIdGenerator(redis, store);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void usesThePerChannelCounter() {
        when(values.increment("channel:seq:dm:1:2")).thenReturn(42L);
        assertThat(generator.next("dm:1:2")).isEqualTo(42);
        verify(store, never()).maxMessageId("dm:1:2");
    }

    @Test
    void firstIdOfANewChannelIsOne() {
        when(values.increment("channel:seq:g:5")).thenReturn(1L);
        when(store.maxMessageId("g:5")).thenReturn(0L);
        assertThat(generator.next("g:5")).isEqualTo(1);
    }

    @Test
    void lostCounterIsReseededAfterThePersistedMaximum() {
        when(values.increment("channel:seq:g:5")).thenReturn(1L);
        when(store.maxMessageId("g:5")).thenReturn(500L);
        when(values.increment("channel:seq:g:5", 500L)).thenReturn(501L);
        assertThat(generator.next("g:5")).isEqualTo(501);
    }
}
