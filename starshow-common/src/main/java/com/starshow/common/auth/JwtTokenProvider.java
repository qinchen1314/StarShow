package com.starshow.common.auth;

import com.starshow.common.security.SecuritySecretValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 统一 JWT 签发与验签工具（HS256，供 starshow-gateway 与 starshow-user 共同复用）
 */
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long defaultExpireSeconds;

    public JwtTokenProvider(String secret, long defaultExpireSeconds) {
        SecuritySecretValidator.validateJwtSecret(secret);
        if (defaultExpireSeconds <= 0) {
            throw new IllegalArgumentException("JWT expireSeconds must be positive");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.defaultExpireSeconds = defaultExpireSeconds;
    }

    public GeneratedToken generateToken(long userId) {
        return generateToken(userId, defaultExpireSeconds);
    }

    public GeneratedToken generateToken(long userId, long expireSeconds) {
        Instant now = Instant.now();
        Instant expireAt = now.plusSeconds(expireSeconds);
        String jti = UUID.randomUUID().toString().replace("-", "");

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expireAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        return new GeneratedToken(token, jti, expireAt.getEpochSecond());
    }

    /**
     * 验签并解析 JWT，过期会抛出 ExpiredJwtException，签名篡改或格式非法会抛出 JwtException
     */
    public JwtPayload parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String sub = claims.getSubject();
        String jti = claims.getId();
        Date exp = claims.getExpiration();
        if (sub == null || jti == null || jti.isBlank() || exp == null) {
            throw new IllegalArgumentException("JWT claims missing required fields");
        }
        long userId = Long.parseLong(sub);
        return new JwtPayload(userId, jti, exp.toInstant().getEpochSecond());
    }

    public record GeneratedToken(String token, String jti, long expireEpochSec) {
    }

    public record JwtPayload(long userId, String jti, long expireEpochSec) {
    }
}
