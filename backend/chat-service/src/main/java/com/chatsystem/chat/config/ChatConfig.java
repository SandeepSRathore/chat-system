package com.chatsystem.chat.config;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.Topics;
import com.chatsystem.common.auth.JwtHandshakeInterceptor;
import com.chatsystem.common.auth.JwtService;
import com.chatsystem.chat.message.DeliveryListener;
import com.chatsystem.chat.ws.ChatWebSocketHandler;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class ChatConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final ChatServerProperties props;
    private final JwtService jwtService;

    public ChatConfig(ChatWebSocketHandler handler, ChatServerProperties props, JwtService jwtService) {
        this.handler = handler;
        this.props = props;
        this.jwtService = jwtService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
                .addInterceptors(new JwtHandshakeInterceptor(jwtService))
                // The token in the query string authenticates the socket, so any origin may connect.
                .setAllowedOriginPatterns("*");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(Duration.ofMinutes(5).toMillis());
        return container;
    }

    /** The message sync queue. 6 partitions keyed by channel id: per-channel order, parallel across channels. */
    @Bean
    NewTopic messageSyncTopic() {
        return TopicBuilder.name(Topics.MESSAGE_SYNC).partitions(6).replicas(1).build();
    }

    /** Receives deliveries the router addressed to this particular chat server. */
    @Bean
    RedisMessageListenerContainer deliveryListenerContainer(RedisConnectionFactory connectionFactory,
                                                            DeliveryListener listener) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(RedisKeys.deliverChannel(props.serverId())));
        return container;
    }
}
