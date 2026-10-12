package com.chatsystem.chat.config;

import com.chatsystem.common.auth.JwtService;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JwtConfig {

    @Bean
    JwtService jwtService(ChatServerProperties props) {
        // Chat servers only verify tokens, so the issuing TTL is irrelevant here.
        return new JwtService(props.jwt().secret(), Duration.ofDays(1));
    }
}
