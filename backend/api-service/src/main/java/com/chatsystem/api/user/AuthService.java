package com.chatsystem.api.user;

import com.chatsystem.api.error.ApiException;
import com.chatsystem.common.auth.JwtService;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public record TokenResponse(String token, UserView user) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public TokenResponse register(String username, String displayName, String password) {
        String normalized = username.toLowerCase(Locale.ROOT);
        try {
            UserView user = users.insert(normalized, displayName.strip(), passwordEncoder.encode(password));
            return new TokenResponse(jwtService.issue(user.id(), user.username()), user);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Username is already taken");
        }
    }

    public TokenResponse login(String username, String password) {
        var credentials = users.findCredentials(username.toLowerCase(Locale.ROOT))
                .filter(c -> passwordEncoder.matches(password, c.passwordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        return new TokenResponse(jwtService.issue(credentials.id(), credentials.username()), credentials.view());
    }
}
