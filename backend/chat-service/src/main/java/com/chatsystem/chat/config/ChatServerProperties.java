package com.chatsystem.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param serverId     unique id of this chat server (also its ZooKeeper instance id)
 * @param publicWsUrl  URL clients use to reach this server directly, advertised via service discovery
 */
@ConfigurationProperties("chat")
public record ChatServerProperties(
        String serverId,
        String publicWsUrl,
        String zookeeperConnect,
        int maxMessageLength,
        Jwt jwt) {

    public record Jwt(String secret) {
    }
}
