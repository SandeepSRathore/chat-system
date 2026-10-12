package com.chatsystem.api.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "3-32 letters, digits or underscores")
            String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(min = 6, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req.username(), req.displayName(), req.password());
    }

    @PostMapping("/login")
    AuthService.TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password());
    }
}
