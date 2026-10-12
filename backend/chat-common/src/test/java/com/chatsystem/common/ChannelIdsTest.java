package com.chatsystem.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChannelIdsTest {

    @Test
    void directChannelIsIndependentOfArgumentOrder() {
        assertThat(ChannelIds.direct(7, 3)).isEqualTo("dm:3:7").isEqualTo(ChannelIds.direct(3, 7));
    }

    @Test
    void directMembersRoundTrip() {
        assertThat(ChannelIds.directMembers(ChannelIds.direct(42, 9))).containsExactly(9L, 42L);
    }

    @Test
    void groupRoundTrip() {
        String id = ChannelIds.group(15);
        assertThat(ChannelIds.isGroup(id)).isTrue();
        assertThat(ChannelIds.isDirect(id)).isFalse();
        assertThat(ChannelIds.groupId(id)).isEqualTo(15);
    }

    @Test
    void rejectsSelfConversationAndMalformedIds() {
        assertThatThrownBy(() -> ChannelIds.direct(1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelIds.directMembers("g:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelIds.directMembers("dm:1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trailingIdParsesKeysAndChannels() {
        assertThat(RedisKeys.trailingId("presence.user.42")).isEqualTo(42);
        assertThat(RedisKeys.trailingId("presence:17")).isEqualTo(17);
        assertThat(RedisKeys.trailingId("friends.changed.5")).isEqualTo(5);
    }
}
