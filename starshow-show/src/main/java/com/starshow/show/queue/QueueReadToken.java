package com.starshow.show.queue;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.security.SecuritySecretValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
class QueueReadToken {
    private static final String PURPOSE = "queue-read";
    private static final long LIFETIME_MS = 300_000;
    private final SecretKey key;
    private final JwtParser parser;
    private final Clock clock;

    @Autowired
    QueueReadToken(WaitingRoomProperties properties, StarShowSecurityProperties security) {
        this(properties.getReadSecret(), security.getJwtSecret(), Clock.systemUTC());
    }

    QueueReadToken(String secret, String jwtSecret, Clock clock) {
        if (Objects.equals(secret, jwtSecret)) {
            throw new IllegalStateException("starshow.waiting-room.read-secret must be independent of the auth JWT key");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("starshow.waiting-room.read-secret is required");
        }
        SecuritySecretValidator.validateJwtSecret(secret);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
        this.parser = Jwts.parser().verifyWith(key).require("purpose", PURPOSE)
                .clock(() -> Date.from(clock.instant())).build();
    }

    String issue(long showId, long userId, int version, String queueId, long now, long endAt) {
        long until = Math.min(endAt, now + LIFETIME_MS);
        return Jwts.builder().claim("purpose", PURPOSE).subject(Long.toString(userId))
                .claim("show", Long.toString(showId)).claim("version", version)
                .claim("queue", queueId).claim("until", until)
                .expiration(Date.from(Instant.ofEpochSecond((until + 999) / 1000)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    Bound read(String token, long showId, Long signedInUser) {
        try {
            if (token.length() > 2048) {
                throw new IllegalArgumentException("Oversized queue credential");
            }
            var signed = parser.parseSignedClaims(token);
            Claims claims = signed.getPayload();
            long userId = Long.parseLong(claims.getSubject());
            long boundShow = Long.parseLong(claims.get("show", String.class));
            Integer version = claims.get("version", Integer.class);
            Long until = claims.get("until", Long.class);
            String queueId = claims.get("queue", String.class);
            if (!"HS256".equals(signed.getHeader().getAlgorithm()) || boundShow != showId || userId <= 0
                    || version == null || version <= 0 || until == null || clock.millis() >= until
                    || queueId == null || !UUID.fromString(queueId).toString().equals(queueId)
                    || (signedInUser != null && signedInUser != userId)) {
                throw new IllegalArgumentException("Invalid queue binding");
            }
            return new Bound(userId, version, queueId);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    record Bound(long userId, int version, String queueId) {}
}
