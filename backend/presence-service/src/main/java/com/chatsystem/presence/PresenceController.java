package com.chatsystem.presence;

import com.chatsystem.common.auth.AuthUser;
import com.chatsystem.common.auth.JwtService;
import com.chatsystem.common.wire.PresenceStatus;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/presence")
public class PresenceController {

    private static final int MAX_LOOKUP = 100;

    private final PresenceService presence;
    private final JwtService jwtService;

    public PresenceController(PresenceService presence, JwtService jwtService) {
        this.presence = presence;
        this.jwtService = jwtService;
    }

    /** On-demand lookup, used for group members (the book: don't fan out presence to big groups). */
    @GetMapping
    List<PresenceStatus> lookup(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                @RequestParam List<Long> userIds) {
        authenticate(auth);
        if (userIds.size() > MAX_LOOKUP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_LOOKUP + " users per lookup");
        }
        return presence.statuses(userIds);
    }

    @PostMapping("/offline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void offline(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        presence.goOffline(authenticate(auth).id());
    }

    private AuthUser authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        try {
            return jwtService.verify(header.substring("Bearer ".length()));
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
