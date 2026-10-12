package com.chatsystem.api.friend;

import com.chatsystem.api.config.CurrentUser;
import com.chatsystem.api.user.UserView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    @GetMapping
    List<UserView> list(@AuthenticationPrincipal Jwt jwt) {
        return friendService.friendsOf(CurrentUser.id(jwt));
    }

    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    UserView add(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId) {
        return friendService.addFriend(CurrentUser.id(jwt), userId);
    }
}
