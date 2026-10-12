package com.chatsystem.common.auth;

import org.springframework.security.oauth2.jwt.Jwt;

public record AuthUser(long id, String username) {

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString(JwtService.USERNAME_CLAIM));
    }
}
