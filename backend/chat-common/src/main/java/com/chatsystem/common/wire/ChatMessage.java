package com.chatsystem.common.wire;

/** A message as it travels through Kafka, Redis pub/sub and the WebSocket. {@code createdAt} is epoch millis. */
public record ChatMessage(String channelId, long messageId, long senderId, String content, long createdAt) {
}
