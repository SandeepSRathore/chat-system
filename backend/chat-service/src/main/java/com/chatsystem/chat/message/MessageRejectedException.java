package com.chatsystem.chat.message;

/** A send that is refused before it reaches the sync queue; the reason is shown to the client. */
public class MessageRejectedException extends RuntimeException {

    public MessageRejectedException(String reason) {
        super(reason);
    }
}
