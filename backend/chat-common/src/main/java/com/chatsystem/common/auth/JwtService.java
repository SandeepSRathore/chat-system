package com.chatsystem.common.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HS256 tokens shared by all services: api-service issues them, chat and presence servers
 * verify them during the WebSocket handshake.
 */
public class JwtService {

    static final String USERNAME_CLAIM = "username";
    private static final String ISSUER = "chat-system";
    /** Shipped as a default in an early commit, so anyone can forge tokens with it. */
    private static final String PUBLISHED_EXAMPLE_SECRET = "change-me-change-me-change-me-change-me-0123456789";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(String secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    public JwtService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "CHAT_JWT_SECRET is not set. Generate one with `openssl rand -base64 48` and start every service with it.");
        }
        if (PUBLISHED_EXAMPLE_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "CHAT_JWT_SECRET is the example value from this project's public history; generate your own.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes for HS256");
        }
        SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(long userId, String username) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim(USERNAME_CLAIM, username)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** @throws JwtException if the token is malformed, tampered with or expired */
    public AuthUser verify(String token) {
        return AuthUser.from(decoder.decode(token));
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
