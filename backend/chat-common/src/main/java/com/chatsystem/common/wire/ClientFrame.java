package com.chatsystem.common.wire;

/**
 * Anything a client sends over a WebSocket.
 * <ul>
 *   <li>chat: {@code SEND} (clientMsgId, channelId, content), {@code PING}</li>
 *   <li>presence: {@code HEARTBEAT}</li>
 * </ul>
 */
public record ClientFrame(String type, String clientMsgId, String channelId, String content) {

    public static final String SEND = "SEND";
    public static final String PING = "PING";
    public static final String HEARTBEAT = "HEARTBEAT";
}
