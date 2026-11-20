package com.starshow.user.config;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.security.SecuritySecretValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实证 ISSUE-002：用户服务/聚合应用缺失任意 1 项安全凭据、AES 非 32 字节或使用公开占位值均拒绝启动
 */
class UserSecurityValidatorTest {

    @Test
    void shouldPassWhenAllFourIndependentSecretsAreConfigured() {
        StarShowSecurityProperties props = validProps();
        assertDoesNotThrow(() -> new UserSecurityValidator(props).afterPropertiesSet());
    }

    @Test
    void shouldFailWhenAnyRequiredSecretIsMissingOrPlaceholder() {
        StarShowSecurityProperties missingJwt = validProps();
        missingJwt.setJwtSecret("");
        IllegalStateException ex1 = assertThrows(IllegalStateException.class,
                () -> new UserSecurityValidator(missingJwt).afterPropertiesSet());
        assertTrue(ex1.getMessage().contains(SecuritySecretValidator.PROP_JWT_SECRET));

        StarShowSecurityProperties badAes = validProps();
        badAes.setAesKey("0123456789abcdef0123456789abcdef"); // 公开占位
        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> new UserSecurityValidator(badAes).afterPropertiesSet());
        assertTrue(ex2.getMessage().contains(SecuritySecretValidator.PROP_AES_KEY));

        StarShowSecurityProperties missingPepper = validProps();
        missingPepper.setIdcardPepper(null);
        IllegalStateException ex3 = assertThrows(IllegalStateException.class,
                () -> new UserSecurityValidator(missingPepper).afterPropertiesSet());
        assertTrue(ex3.getMessage().contains(SecuritySecretValidator.PROP_IDCARD_PEPPER));

        StarShowSecurityProperties missingInternal = validProps();
        missingInternal.setInternalGatewaySecret("starshow-dev-internal-gateway-secret");
        IllegalStateException ex4 = assertThrows(IllegalStateException.class,
                () -> new UserSecurityValidator(missingInternal).afterPropertiesSet());
        assertTrue(ex4.getMessage().contains(SecuritySecretValidator.PROP_INTERNAL_GATEWAY_SECRET));
    }

    private StarShowSecurityProperties validProps() {
        StarShowSecurityProperties props = new StarShowSecurityProperties();
        props.setJwtSecret("user-sec-test-jwt-key-9a8b7c6d5e4f3a2b1c0d");
        props.setAesKey("A7f9K2m4P6q8R1t3V5w7X9y0Z2b4D6f8");
        props.setIdcardPepper("user-sec-test-pepper-7f8e9d6c5b4a");
        props.setInternalGatewaySecret("user-sec-test-internal-5c4b3a2f1e");
        return props;
    }
}
