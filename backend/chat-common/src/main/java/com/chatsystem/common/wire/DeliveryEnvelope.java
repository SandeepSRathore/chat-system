package com.chatsystem.common.wire;

/** Published by the router onto {@code chat.deliver.{serverId}} for the chat server holding the recipient's socket. */
public record DeliveryEnvelope(long recipientId, ChatMessage message) {
}
