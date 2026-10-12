package com.chatsystem.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat")
public record PresenceProperties(Presence presence, Jwt jwt) {

    public record Presence(Duration ttl) {
    }

    public record Jwt(String secret) {
    }
}
