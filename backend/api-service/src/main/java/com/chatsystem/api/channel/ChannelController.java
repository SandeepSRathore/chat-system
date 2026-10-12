package com.chatsystem.api.channel;

import com.chatsystem.api.config.CurrentUser;
import com.chatsystem.common.wire.ChatMessage;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    List<ChannelView> list(@AuthenticationPrincipal Jwt jwt) {
        return channelService.channelsOf(CurrentUser.id(jwt));
    }

    /** Chronological page of history; pass the oldest loaded {@code messageId} as {@code before} to scroll back. */
    @GetMapping("/{channelId}/messages")
    List<ChatMessage> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable String channelId,
                               @RequestParam(required = false) Long before,
                               @RequestParam(defaultValue = "50") int limit) {
        return channelService.history(CurrentUser.id(jwt), channelId, before, limit);
    }
}
