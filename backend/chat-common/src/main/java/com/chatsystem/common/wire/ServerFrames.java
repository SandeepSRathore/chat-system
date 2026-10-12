package com.chatsystem.common.wire;

import java.util.List;

/** Everything a server pushes over a WebSocket. Each frame carries a {@code type} discriminator. */
public final class ServerFrames {

    private ServerFrames() {
    }

    /** Sent to the author once the message is durably in the sync queue and has its id. */
    public record Ack(String type, String clientMsgId, ChatMessage message) {
        public Ack(String clientMsgId, ChatMessage message) {
            this("ACK", clientMsgId, message);
        }
    }

    public record Message(String type, ChatMessage message) {
        public Message(ChatMessage message) {
            this("MESSAGE", message);
        }
    }

    public record Error(String type, String clientMsgId, String error) {
        public Error(String clientMsgId, String error) {
            this("ERROR", clientMsgId, error);
        }
    }

    public record Connected(String type, String serverId, long userId) {
        public Connected(String serverId, long userId) {
            this("CONNECTED", serverId, userId);
        }
    }

    public record Pong(String type) {
        public Pong() {
            this("PONG");
        }
    }

    public record Presence(String type, PresenceStatus presence) {
        public Presence(PresenceStatus presence) {
            this("PRESENCE", presence);
        }
    }

    public record PresenceSnapshot(String type, List<PresenceStatus> presences) {
        public PresenceSnapshot(List<PresenceStatus> presences) {
            this("SNAPSHOT", presences);
        }
    }
}
