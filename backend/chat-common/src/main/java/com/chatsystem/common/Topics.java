package com.chatsystem.common;

/** Kafka topics. */
public final class Topics {

    /** The book's "message sync queue". Keyed by channel id so ordering holds within a channel. */
    public static final String MESSAGE_SYNC = "message.sync";

    private Topics() {
    }
}
