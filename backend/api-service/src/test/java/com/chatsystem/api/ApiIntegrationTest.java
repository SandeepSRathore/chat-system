package com.chatsystem.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatsystem.api.config.CuratorConfig;
import com.chatsystem.common.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.awaitility.Awaitility;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"chat.max-group-size=3", "chat.jwt.secret=integration-test-secret-integration-test-secret"})
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

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

    record Response(HttpStatusCode status, String body) {
    }

    record Session(String token, long id) {
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1").build();
    }

    private Session register(String username) {
        Map<String, Object> body = http.post().uri("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "displayName", username.toUpperCase(), "password", "secret1"))
                .retrieve().body(new ParameterizedTypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) body.get("user");
        return new Session((String) body.get("token"), ((Number) user.get("id")).longValue());
    }

    private Response call(HttpMethod method, String uri, Session as, Object body) {
        var spec = http.method(method).uri(uri).header("Authorization", "Bearer " + as.token());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> new Response(res.getStatusCode(), new String(res.getBody().readAllBytes())));
    }

    @Test
    void friendsGroupsAndHistoryAccess() {
        Session alice = register("alice_it");
        Session bob = register("bob_it");
        Session carol = register("carol_it");
        Session dave = register("dave_it");

        String dm = "dm:" + Math.min(alice.id(), bob.id()) + ":" + Math.max(alice.id(), bob.id());

        // A request alone grants nothing: no conversation, no history, no channel membership.
        assertThat(call(HttpMethod.POST, "/friends/requests/" + bob.id(), alice, null).body()).contains("\"PENDING\"");
        assertThat(call(HttpMethod.GET, "/channels", alice, null).body()).doesNotContain(dm);
        assertThat(call(HttpMethod.GET, "/channels/" + dm + "/messages", alice, null).status().value()).isEqualTo(403);
        assertThat(redisTemplate.hasKey(RedisKeys.channelMembers(dm))).isFalse();
        assertThat(call(HttpMethod.GET, "/friends/requests", bob, null).body()).contains("\"incoming\":[{\"id\":" + alice.id());

        // Only the addressee can accept.
        assertThat(call(HttpMethod.POST, "/friends/requests/" + alice.id() + "/accept", carol, null).status().value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/friends/requests/" + alice.id() + "/accept", bob, null).status().value())
                .isEqualTo(200);

        // Once accepted, friendship is mutual and mirrored into Redis for the chat/presence servers.
        assertThat(call(HttpMethod.GET, "/channels", bob, null).body()).contains(dm);
        assertThat(call(HttpMethod.GET, "/friends/requests", bob, null).body()).isEqualTo("{\"incoming\":[],\"outgoing\":[]}");
        // The new channel's message-id counter starts at 0, so chat servers never read Cassandra for it.
        assertThat(redisTemplate.opsForValue().get(RedisKeys.channelSequence(dm))).isEqualTo("0");
        Awaitility.await().untilAsserted(() -> {
            assertThat(redisTemplate.opsForSet().members(RedisKeys.channelMembers(dm)))
                    .containsExactlyInAnyOrder(String.valueOf(alice.id()), String.valueOf(bob.id()));
            assertThat(redisTemplate.opsForSet().isMember(RedisKeys.friends(bob.id()), String.valueOf(alice.id()))).isTrue();
        });

        // History: participants may read, outsiders may not.
        assertThat(call(HttpMethod.GET, "/channels/" + dm + "/messages", alice, null))
                .isEqualTo(new Response(HttpStatusCode.valueOf(200), "[]"));
        assertThat(call(HttpMethod.GET, "/channels/" + dm + "/messages", carol, null).status().value()).isEqualTo(403);

        // Group cap (3 in this test) includes the owner.
        Response tooBig = call(HttpMethod.POST, "/groups", alice,
                Map.of("name", "Too big", "memberIds", List.of(bob.id(), carol.id(), dave.id())));
        assertThat(tooBig.status().value()).isEqualTo(400);
        assertThat(tooBig.body()).contains("at most 3");

        Response group = call(HttpMethod.POST, "/groups", alice,
                Map.of("name", "Team", "memberIds", List.of(bob.id(), carol.id())));
        assertThat(group.status().value()).isEqualTo(201);
        assertThat(call(HttpMethod.GET, "/channels", carol, null).body()).contains("\"Team\"");
        Awaitility.await().untilAsserted(() -> assertThat(redisTemplate.keys("channel:seq:g:*")).hasSize(1));

        // Only the owner can remove others; members can leave.
        long groupId = Long.parseLong(group.body().replaceAll(".*\"id\":(\\d+),\"channelId\".*", "$1"));
        assertThat(call(HttpMethod.DELETE, "/groups/" + groupId + "/members/" + carol.id(), bob, null).status().value())
                .isEqualTo(403);
        assertThat(call(HttpMethod.DELETE, "/groups/" + groupId + "/members/" + carol.id(), carol, null).status().value())
                .isEqualTo(204);
        assertThat(call(HttpMethod.GET, "/channels/g:" + groupId + "/messages", carol, null).status().value())
                .isEqualTo(403);
    }

    @Test
    void crossingRequestsBecomeFriendshipAndRequestsCanBeDeclined() {
        Session erin = register("erin_it");
        Session finn = register("finn_it");
        Session gus = register("gus_it");

        call(HttpMethod.POST, "/friends/requests/" + finn.id(), erin, null);
        // Finn asking Erin back is mutual consent.
        assertThat(call(HttpMethod.POST, "/friends/requests/" + erin.id(), finn, null).body()).contains("\"ACCEPTED\"");
        assertThat(call(HttpMethod.GET, "/friends", erin, null).body()).contains("finn_it");

        call(HttpMethod.POST, "/friends/requests/" + gus.id(), erin, null);
        assertThat(call(HttpMethod.DELETE, "/friends/requests/" + erin.id(), gus, null).status().value()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, "/friends/requests/" + erin.id() + "/accept", gus, null).status().value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/friends", gus, null).body()).isEqualTo("[]");
    }

    @Test
    void discoveryPicksTheLeastLoadedChatServer() throws Exception {
        Session user = register("discover_it");
        assertThat(call(HttpMethod.GET, "/chat/server", user, null).status().value()).isEqualTo(503);

        try (var client = CuratorFrameworkFactory.newClient(zookeeper.getConnectString(), new RetryOneTime(100))) {
            client.start();
            ServiceDiscovery<String> discovery = ServiceDiscoveryBuilder.builder(String.class).client(client)
                    .basePath(CuratorConfig.BASE_PATH).serializer(new JsonInstanceSerializer<>(String.class)).build();
            discovery.start();
            for (String id : List.of("busy", "idle")) {
                discovery.registerService(ServiceInstance.<String>builder()
                        .name(CuratorConfig.CHAT_SERVICE_NAME).id(id).payload("ws://" + id + "/ws/chat").build());
            }
            redisTemplate.opsForValue().set(RedisKeys.serverConnections("busy"), "10");
            redisTemplate.opsForValue().set(RedisKeys.serverConnections("idle"), "2");

            Awaitility.await().untilAsserted(() ->
                    assertThat(call(HttpMethod.GET, "/chat/server", user, null).body()).contains("\"serverId\":\"idle\""));
            discovery.close();
        }
    }

    @Test
    void protectedEndpointsRequireAToken() {
        int status = http.get().uri("/channels").exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(401);
    }
}
