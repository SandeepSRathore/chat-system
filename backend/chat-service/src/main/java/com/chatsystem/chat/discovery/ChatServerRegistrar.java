package com.chatsystem.chat.discovery;

import com.chatsystem.chat.config.ChatServerProperties;
import com.chatsystem.chat.config.CuratorConfig;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Registers this chat server in ZooKeeper once it is ready to accept WebSocket connections. */
@Component
class ChatServerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ChatServerRegistrar.class);

    private final ServiceDiscovery<String> discovery;
    private final ChatServerProperties props;

    ChatServerRegistrar(ServiceDiscovery<String> discovery, ChatServerProperties props) {
        this.discovery = discovery;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void register() throws Exception {
        ServiceInstance<String> instance = ServiceInstance.<String>builder()
                .name(CuratorConfig.CHAT_SERVICE_NAME)
                .id(props.serverId())
                .payload(props.publicWsUrl())
                .serviceType(ServiceType.DYNAMIC) // ephemeral node
                .build();
        discovery.registerService(instance);
        log.info("Registered chat server {} at {}", props.serverId(), props.publicWsUrl());
    }
}
