package com.starshow.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecuritySecretValidatorTest {

    @Test
    void shouldAcceptStrongIndependentSecrets() {
        assertDoesNotThrow(() -> SecuritySecretValidator.validateJwtSecret("jwt-prod-k9x2m4p7q1w8e5r3t6y0u2i4o6p8a1s3"));
        assertDoesNotThrow(() -> SecuritySecretValidator.validateAesKey256("A7f9K2m4P6q8R1t3V5w7X9y0Z2b4D6f8"));
        assertDoesNotThrow(() -> SecuritySecretValidator.validateIdcardPepper("pepper-9a8b7c6d5e4f3a2b1c0d"));
        assertDoesNotThrow(() -> SecuritySecretValidator.validateInternalGatewaySecret("gw-internal-sec-7f8e9d6c5b4a"));
    }

    @Test
    void shouldRejectMissingShortOrPlaceholderSecretsWithoutLeakingSecretValue() {
        String leakedCandidate = "starshow-dev-jwt-secret-key-please-change-in-production-32bytes";
        IllegalStateException jwtEx = assertThrows(IllegalStateException.class,
                () -> SecuritySecretValidator.validateJwtSecret(leakedCandidate));
        assertTrue(jwtEx.getMessage().contains(SecuritySecretValidator.PROP_JWT_SECRET));
        assertFalse(jwtEx.getMessage().contains(leakedCandidate));

        // 16 字节 AES-128 必须被拒（ISSUE-002 要求 AES-256 恰为 32 字节）
        String aes16 = "1234567890abcdef";
        IllegalStateException aesEx = assertThrows(IllegalStateException.class,
                () -> SecuritySecretValidator.validateAesKey256(aes16));
        assertTrue(aesEx.getMessage().contains(SecuritySecretValidator.PROP_AES_KEY));
        assertFalse(aesEx.getMessage().contains(aes16));

        // 公开占位 AES 32 字节必须被拒
        assertThrows(IllegalStateException.class,
                () -> SecuritySecretValidator.validateAesKey256("0123456789abcdef0123456789abcdef"));

        // 空白或占位 pepper / 内部密钥必须被拒
        assertThrows(IllegalStateException.class, () -> SecuritySecretValidator.validateIdcardPepper("  "));
        assertThrows(IllegalStateException.class,
                () -> SecuritySecretValidator.validateIdcardPepper("replace-with-random-idcard-pepper"));
        assertThrows(IllegalStateException.class, () -> SecuritySecretValidator.validateInternalGatewaySecret(null));
        assertThrows(IllegalStateException.class,
                () -> SecuritySecretValidator.validateInternalGatewaySecret("starshow-dev-internal-gateway-secret"));
    }
}
