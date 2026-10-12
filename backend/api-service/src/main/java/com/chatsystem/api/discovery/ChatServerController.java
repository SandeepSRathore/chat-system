package com.chatsystem.api.discovery;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatServerController {

    private final ChatServerDiscovery discovery;

    public ChatServerController(ChatServerDiscovery discovery) {
        this.discovery = discovery;
    }

    /** Which chat server this client should open its WebSocket to. */
    @GetMapping("/server")
    ChatServerDiscovery.ChatServerView server() {
        return discovery.pick();
    }

    @GetMapping("/servers")
    List<ChatServerDiscovery.ChatServerView> servers() {
        return discovery.available();
    }
}
