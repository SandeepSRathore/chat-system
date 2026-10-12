package com.chatsystem.chat.ws;

import com.chatsystem.chat.config.ChatServerProperties;
import com.chatsystem.chat.message.MessageRejectedException;
import com.chatsystem.chat.message.MessageService;
import com.chatsystem.chat.session.SessionRegistry;
import com.chatsystem.common.auth.AuthUser;
import com.chatsystem.common.auth.JwtHandshakeInterceptor;
import com.chatsystem.common.wire.ClientFrame;
import com.chatsystem.common.wire.ServerFrames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String OUTBOUND = "outbound";

    private final SessionRegistry sessions;
    private final MessageService messageService;
    private final JsonMapper json;
    private final String serverId;

    public ChatWebSocketHandler(SessionRegistry sessions, MessageService messageService, JsonMapper json,
                                ChatServerProperties props) {
        this.sessions = sessions;
        this.messageService = messageService;
        this.json = json;
        this.serverId = props.serverId();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AuthUser user = user(session);
        WebSocketSession outbound = sessions.register(user.id(), session);
        session.getAttributes().put(OUTBOUND, outbound);
        sessions.send(outbound, new ServerFrames.Connected(serverId, user.id()));
        log.debug("User {} connected to {}", user.id(), serverId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage text) {
        WebSocketSession outbound = (WebSocketSession) session.getAttributes().get(OUTBOUND);
        ClientFrame frame;
        try {
            frame = json.readValue(text.getPayload(), ClientFrame.class);
        } catch (JacksonException e) {
            sessions.send(outbound, new ServerFrames.Error(null, "Malformed frame"));
            return;
        }
        switch (frame.type() == null ? "" : frame.type()) {
            case ClientFrame.SEND -> handleSend(user(session), frame, outbound);
            case ClientFrame.PING -> sessions.send(outbound, new ServerFrames.Pong());
            default -> sessions.send(outbound, new ServerFrames.Error(frame.clientMsgId(), "Unknown frame type"));
        }
    }

    private void handleSend(AuthUser user, ClientFrame frame, WebSocketSession outbound) {
        try {
            messageService.send(user.id(), frame.clientMsgId(), frame.channelId(), frame.content())
                    .whenComplete((message, error) -> {
                        if (error == null) {
                            if (message != null) {
                                sessions.send(outbound, new ServerFrames.Ack(frame.clientMsgId(), message));
                            }
                        } else {
                            log.warn("Send failed for user {}: {}", user.id(), error.getMessage());
                            sessions.send(outbound, new ServerFrames.Error(frame.clientMsgId(), "Could not send message"));
                        }
                    });
        } catch (MessageRejectedException e) {
            sessions.send(outbound, new ServerFrames.Error(frame.clientMsgId(), e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Send failed for user {}", user.id(), e);
            sessions.send(outbound, new ServerFrames.Error(frame.clientMsgId(), "Could not send message"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.unregister(user(session).id(), session);
    }

    private static AuthUser user(WebSocketSession session) {
        return (AuthUser) session.getAttributes().get(JwtHandshakeInterceptor.USER_ATTRIBUTE);
    }
}
