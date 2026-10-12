package com.chatsystem.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatsystem.chat.message.MessageStore;
import com.chatsystem.common.RedisKeys;
import com.chatsystem.common.auth.JwtService;
import java.time.Duration;
import org.apache.curator.test.TestingServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** WebSocket → id generator → Kafka → (persister → Cassandra) + (router → Redis pub/sub → WebSocket). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "chat.jwt.secret=integration-test-secret-integration-test-secret")
@Testcontainers
class ChatFlowIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static CassandraContainer cassandra = new CassandraContainer("cassandra:5.0")
            .withInitScript("cassandra-init.cql")
            .withEnv("MAX_HEAP_SIZE", "512M")
            .withEnv("HEAP_NEWSIZE", "128M")
            .withEnv("JVM_OPTS", "-Dcassandra.skip_wait_for_gossip_to_settle=0 -Dcassandra.initial_token=0")
            .withStartupTimeout(Duration.ofMinutes(4));

    static final TestingServer zookeeper;

    static {
        try {
            zookeeper = new TestingServer(true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("chat.zookeeper-connect", zookeeper::getConnectString);
        registry.add("spring.cassandra.keyspace-name", () -> "chat");
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtService jwt;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    MessageStore store;

    @BeforeEach
    void seedMembership() {
        // Normally written by api-service when the friendship is created.
        redisTemplate.opsForSet().add(RedisKeys.channelMembers("dm:1:2"), "1", "2");
    }

    private WsTestClient connect(long userId) throws InterruptedException {
        WsTestClient client = new WsTestClient("ws://localhost:" + port + "/ws/chat?token=" + jwt.issue(userId, "u" + userId));
        client.await("\"CONNECTED\"");
        return client;
    }

    @Test
    void messageIsAckedDeliveredAndPersisted() throws Exception {
        try (WsTestClient alice = connect(1); WsTestClient bob = connect(2)) {
            assertThat(redisTemplate.opsForValue().get(RedisKeys.session(2))).isEqualTo("chat-1");

            alice.send("""
                    {"type":"SEND","clientMsgId":"m-1","channelId":"dm:1:2","content":"hello bob"}""");

            String ack = alice.await("\"ACK\"");
            assertThat(ack).contains("\"clientMsgId\":\"m-1\"").contains("\"messageId\":1");

            String delivered = bob.await("\"MESSAGE\"");
            assertThat(delivered).contains("hello bob").contains("\"senderId\":1");

            Awaitility.await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(store.maxMessageId("dm:1:2")).isEqualTo(1));

            // A retry of the same clientMsgId is acknowledged with the original id, not re-sent.
            alice.send("""
                    {"type":"SEND","clientMsgId":"m-1","channelId":"dm:1:2","content":"hello bob"}""");
            assertThat(alice.await("\"ACK\"")).contains("\"messageId\":1");
        }
    }

    @Test
    void nonMemberSendIsRejected() throws Exception {
        try (WsTestClient mallory = connect(3)) {
            mallory.send("""
                    {"type":"SEND","clientMsgId":"x","channelId":"dm:1:2","content":"let me in"}""");
            assertThat(mallory.await("\"ERROR\"")).contains("not a member");
        }
    }

    @Test
    void handshakeWithoutValidTokenIsRefused() {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> new WsTestClient("ws://localhost:" + port + "/ws/chat?token=garbage"));
        assertThat(failure).isNotNull();
    }
}
