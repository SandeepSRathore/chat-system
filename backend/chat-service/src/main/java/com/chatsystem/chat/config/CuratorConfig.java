package com.chatsystem.chat.config;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CuratorConfig {

    public static final String BASE_PATH = "/chat-system";
    public static final String CHAT_SERVICE_NAME = "chat-server";

    @Bean(initMethod = "start", destroyMethod = "close")
    CuratorFramework curator(ChatServerProperties props) {
        return CuratorFrameworkFactory.newClient(props.zookeeperConnect(), new ExponentialBackoffRetry(1000, 5));
    }

    /** Closing the discovery on shutdown unregisters this server; a crash drops the ephemeral node too. */
    @Bean(initMethod = "start", destroyMethod = "close")
    ServiceDiscovery<String> serviceDiscovery(CuratorFramework curator) {
        return ServiceDiscoveryBuilder.builder(String.class)
                .client(curator)
                .basePath(BASE_PATH)
                .serializer(new JsonInstanceSerializer<>(String.class))
                .build();
    }
}
