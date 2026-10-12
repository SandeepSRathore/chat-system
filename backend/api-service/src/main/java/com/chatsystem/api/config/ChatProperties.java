package com.chatsystem.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat")
public record ChatProperties(Jwt jwt, String zookeeperConnect, int maxGroupSize) {

    public record Jwt(String secret, Duration ttl) {
    }
}
