package com.chatsystem.api.user;

import com.chatsystem.api.config.CurrentUser;
import com.chatsystem.api.error.ApiException;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(CurrentUser.id(jwt)).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @GetMapping("/search")
    List<UserView> search(@AuthenticationPrincipal Jwt jwt, @RequestParam("q") String query) {
        if (query.isBlank()) {
            return List.of();
        }
        return users.search(query.strip(), CurrentUser.id(jwt), 20);
    }
}
