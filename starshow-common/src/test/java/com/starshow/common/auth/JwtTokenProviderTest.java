package com.starshow.common.auth;

import com.starshow.common.security.SecuritySecretValidator;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String VALID_SECRET = "unit-jwt-key-7a9c1e3f5b7d9a1c3e5f7b9d1a3c5e7f";

    @Test
    void shouldGenerateAndParseTokenWithExactClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, 3600);
        long userId = 1880000123456789012L;

        JwtTokenProvider.GeneratedToken generated = provider.generateToken(userId);
        assertNotNull(generated.token());
        assertNotNull(generated.jti());
        assertFalse(generated.jti().isBlank());

        JwtTokenProvider.JwtPayload parsed = provider.parseToken(generated.token());
        assertEquals(userId, parsed.userId());
        assertEquals(generated.jti(), parsed.jti());
        assertEquals(generated.expireEpochSec(), parsed.expireEpochSec());
    }

    @Test
    void shouldThrowExpiredJwtExceptionForExpiredToken() {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, 3600);
        JwtTokenProvider.GeneratedToken expired = provider.generateToken(10001L, -5);

        assertThrows(ExpiredJwtException.class, () -> provider.parseToken(expired.token()));
    }

    @Test
    void shouldRejectTamperedPayloadOrWrongSignature() {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, 3600);
        JwtTokenProvider otherProvider = new JwtTokenProvider("other-jwt-key-9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c", 3600);

        String token = provider.generateToken(10001L).token();
        assertThrows(JwtException.class, () -> otherProvider.parseToken(token));

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        String tamperedPayloadJson = "{\"sub\":\"99999\",\"jti\":\"fake\",\"exp\":9999999999}";
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayloadJson.getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(JwtException.class, () -> provider.parseToken(tamperedToken));
    }

    @Test
    void shouldRejectSecretShorterThan32BytesOrPublicPlaceholder() {
        IllegalStateException shortEx = assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("too-short-secret", 3600));
        assertTrue(shortEx.getMessage().contains(SecuritySecretValidator.PROP_JWT_SECRET));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(null, 3600));
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("starshow-dev-jwt-secret-key-please-change-in-production-32bytes", 3600));
    }
}
