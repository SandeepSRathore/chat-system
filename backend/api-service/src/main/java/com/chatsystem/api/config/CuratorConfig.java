package com.chatsystem.api.config;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceCache;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client side of the book's service discovery: watches the chat servers registered in ZooKeeper. */
@Configuration
public class CuratorConfig {

    public static final String BASE_PATH = "/chat-system";
    public static final String CHAT_SERVICE_NAME = "chat-server";

    @Bean(initMethod = "start", destroyMethod = "close")
    CuratorFramework curator(ChatProperties props) {
        return CuratorFrameworkFactory.newClient(props.zookeeperConnect(), new ExponentialBackoffRetry(1000, 5));
    }

    /** Instance payload is the public WebSocket URL of the chat server. */
    @Bean(initMethod = "start", destroyMethod = "close")
    ServiceDiscovery<String> serviceDiscovery(CuratorFramework curator) {
        return ServiceDiscoveryBuilder.builder(String.class)
                .client(curator)
                .basePath(BASE_PATH)
                .serializer(new JsonInstanceSerializer<>(String.class))
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ServiceCache<String> chatServerCache(ServiceDiscovery<String> discovery) {
        return discovery.serviceCacheBuilder().name(CHAT_SERVICE_NAME).build();
    }
}
