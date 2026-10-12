package com.chatsystem.presence;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.auth.JwtHandshakeInterceptor;
import com.chatsystem.common.auth.JwtService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class PresenceConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PresenceConfig.class);

    private final PresenceWebSocketHandler handler;
    private final JwtService jwtService;

    PresenceConfig(PresenceWebSocketHandler handler, JwtService jwtService) {
        this.handler = handler;
        this.jwtService = jwtService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/presence")
                .addInterceptors(new JwtHandshakeInterceptor(jwtService))
                .setAllowedOriginPatterns("*");
    }

    @Bean
    RedisMessageListenerContainer presenceListenerContainer(RedisConnectionFactory connectionFactory,
                                                            PresenceEventListener listener) {
        enableKeyExpiryEvents(connectionFactory);
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, List.of(
                new PatternTopic(RedisKeys.PRESENCE_EVENTS_PATTERN),
                new PatternTopic(RedisKeys.FRIENDS_CHANGED_PATTERN),
                new ChannelTopic(RedisKeys.KEY_EXPIRED_CHANNEL)));
        return container;
    }

    /** Offline detection depends on expiry notifications; make sure they are on even without redis.conf. */
    private static void enableKeyExpiryEvents(RedisConnectionFactory connectionFactory) {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().setConfig("notify-keyspace-events", "Ex");
        } catch (RuntimeException e) {
            log.warn("Could not enable keyspace notifications (CONFIG SET denied?); "
                    + "configure notify-keyspace-events=Ex on the Redis server", e);
        }
    }
}

@Configuration
class JwtConfig {

    @Bean
    JwtService jwtService(PresenceProperties props) {
        return new JwtService(props.jwt().secret(), Duration.ofDays(1));
    }
}
