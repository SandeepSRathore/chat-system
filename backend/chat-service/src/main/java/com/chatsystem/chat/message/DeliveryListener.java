package com.chatsystem.chat.message;

import com.chatsystem.chat.session.SessionRegistry;
import com.chatsystem.common.wire.DeliveryEnvelope;
import com.chatsystem.common.wire.ServerFrames;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Last hop: a delivery addressed to this server is pushed down the recipient's WebSocket. */
@Component
public class DeliveryListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryListener.class);

    private final SessionRegistry sessions;
    private final JsonMapper json;

    public DeliveryListener(SessionRegistry sessions, JsonMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        DeliveryEnvelope envelope = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                DeliveryEnvelope.class);
        if (!sessions.sendToUser(envelope.recipientId(), new ServerFrames.Message(envelope.message()))) {
            log.debug("User {} disconnected before delivery; message stays in history", envelope.recipientId());
        }
    }
}
