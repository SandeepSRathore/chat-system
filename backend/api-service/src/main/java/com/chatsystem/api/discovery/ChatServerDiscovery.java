package com.chatsystem.api.discovery;

import com.chatsystem.api.error.ApiException;
import com.chatsystem.common.RedisKeys;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.curator.x.discovery.ServiceCache;
import org.apache.curator.x.discovery.ServiceInstance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The book's service discovery step: "recommend the best chat server for a client".
 * Live servers come from ZooKeeper (ephemeral nodes vanish when a server dies); "best" is the
 * one with the fewest open WebSocket connections, which each server reports to Redis.
 */
@Service
public class ChatServerDiscovery {

    public record ChatServerView(String serverId, String wsUrl, long connections) {
    }

    private final ServiceCache<String> chatServers;
    private final StringRedisTemplate redis;

    public ChatServerDiscovery(ServiceCache<String> chatServers, StringRedisTemplate redis) {
        this.chatServers = chatServers;
        this.redis = redis;
    }

    public ChatServerView pick() {
        return available().stream()
                .min(Comparator.comparingLong(ChatServerView::connections).thenComparing(ChatServerView::serverId))
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No chat server is available"));
    }

    public List<ChatServerView> available() {
        List<ServiceInstance<String>> instances = chatServers.getInstances();
        if (instances.isEmpty()) {
            return List.of();
        }
        List<String> counts = redis.opsForValue().multiGet(
                instances.stream().map(i -> RedisKeys.serverConnections(i.getId())).toList());
        return IntStream.range(0, instances.size())
                .mapToObj(i -> new ChatServerView(instances.get(i).getId(), instances.get(i).getPayload(),
                        parse(counts == null ? null : counts.get(i))))
                .toList();
    }

    private static long parse(String count) {
        return count == null ? 0 : Long.parseLong(count);
    }
}
