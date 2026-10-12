package com.chatsystem.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class PresenceHubTest {

    private final PresenceHub hub = new PresenceHub(JsonMapper.builder().build());

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void connectedUsersWatchTheirFriends() {
        hub.connect(1, session("a"), Set.of(2L, 3L));
        hub.connect(4, session("b"), Set.of(2L));
        assertThat(hub.watchersOf(2)).containsExactlyInAnyOrder(1L, 4L);
        assertThat(hub.watchersOf(3)).containsExactly(1L);
    }

    @Test
    void friendListUpdatesAndDisconnectsMaintainTheIndex() {
        WebSocketSession s = session("a");
        hub.connect(1, s, Set.of(2L, 3L));
        hub.updateFriends(1, Set.of(3L, 5L));
        assertThat(hub.watchersOf(2)).isEmpty();
        assertThat(hub.watchersOf(5)).containsExactly(1L);

        hub.disconnect(1, s);
        assertThat(hub.watchersOf(3)).isEmpty();
        assertThat(hub.isConnected(1)).isFalse();
    }

    @Test
    void closingAReplacedSessionDoesNotDropTheNewOne() {
        WebSocketSession old = session("old");
        hub.connect(1, old, Set.of(2L));
        hub.connect(1, session("new"), Set.of(2L));
        hub.disconnect(1, old);
        assertThat(hub.isConnected(1)).isTrue();
        assertThat(hub.watchersOf(2)).containsExactly(1L);
    }

    @Test
    void onlyBarePresenceKeysCountAsPresenceExpiry() {
        assertThat(PresenceEventListener.isPresenceKey("presence:42")).isTrue();
        assertThat(PresenceEventListener.isPresenceKey("presence:last_active:42")).isFalse();
        assertThat(PresenceEventListener.isPresenceKey("session:42")).isFalse();
    }
}
