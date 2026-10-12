package com.chatsystem.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-42";

    @Test
    void issuedTokenVerifies() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(5));
        AuthUser user = jwt.verify(jwt.issue(12, "alice"));
        assertThat(user).isEqualTo(new AuthUser(12, "alice"));
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        String token = new JwtService(SECRET + "-other", Duration.ofMinutes(5)).issue(1, "mallory");
        assertThatThrownBy(() -> new JwtService(SECRET, Duration.ofMinutes(5)).verify(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        Clock anHourAgo = Clock.offset(Clock.systemUTC(), Duration.ofHours(-1));
        String token = new JwtService(SECRET, Duration.ofMinutes(5), anHourAgo).issue(1, "bob");
        assertThatThrownBy(() -> new JwtService(SECRET, Duration.ofMinutes(5)).verify(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> new JwtService("short", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
