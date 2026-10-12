package com.chatsystem.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Heartbeat → ONLINE; socket gone + missed heartbeats → key expiry → OFFLINE. TTL shortened to 2s. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "chat.presence.ttl=2s")
@Testcontainers
class PresenceIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @LocalServerPort
    int port;

    @Autowired
    JwtService jwt;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void seedFriends() {
        redisTemplate.opsForSet().add(RedisKeys.friends(1), "2");
        redisTemplate.opsForSet().add(RedisKeys.friends(2), "1");
    }

    private WsTestClient connect(long userId) {
        return new WsTestClient("ws://localhost:" + port + "/ws/presence?token=" + jwt.issue(userId, "u" + userId));
    }

    @Test
    void friendSeesOnlineThenOfflineAfterHeartbeatsStop() throws Exception {
        try (WsTestClient alice = connect(1)) {
            assertThat(alice.await("\"SNAPSHOT\"")).contains("\"userId\":2,\"online\":false");

            WsTestClient bob = connect(2);
            assertThat(bob.await("\"SNAPSHOT\"")).contains("\"userId\":1,\"online\":true");
            assertThat(alice.await("\"PRESENCE\"")).contains("\"userId\":2,\"online\":true");

            // Keep alice alive while bob disappears without logging out.
            bob.close();
            Thread heartbeat = Thread.ofVirtual().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    alice.send("{\"type\":\"HEARTBEAT\"}");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            try {
                assertThat(alice.await("\"online\":false")).contains("\"userId\":2");
                assertThat(redisTemplate.hasKey(RedisKeys.presence(1))).isTrue();
            } finally {
                heartbeat.interrupt();
            }
        }
    }

    @Test
    void explicitLogoutIsImmediate() throws Exception {
        try (WsTestClient alice = connect(1)) {
            alice.await("\"SNAPSHOT\"");
            try (WsTestClient bob = connect(2)) {
                alice.await("\"online\":true");
                org.springframework.web.client.RestClient.create("http://localhost:" + port)
                        .post().uri("/api/v1/presence/offline")
                        .header("Authorization", "Bearer " + jwt.issue(2, "u2"))
                        .retrieve().toBodilessEntity();
                assertThat(alice.await("\"online\":false")).contains("\"userId\":2");
            }
        }
    }
}
