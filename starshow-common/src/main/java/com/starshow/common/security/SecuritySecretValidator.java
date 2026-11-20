package com.starshow.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 安全凭据启动期校验器（ISSUE-002）：
 * - 拒绝缺失、空白、长度不足或仓库公开占位值；
 * - 报错信息严格仅包含配置项名称，绝不泄露任何密钥明文或片段。
 */
public final class SecuritySecretValidator {

    public static final String PROP_JWT_SECRET = "starshow.security.jwt-secret";
    public static final String PROP_AES_KEY = "starshow.security.aes-key";
    public static final String PROP_IDCARD_PEPPER = "starshow.security.idcard-pepper";
    public static final String PROP_INTERNAL_GATEWAY_SECRET = "starshow.security.internal-gateway-secret";

    private static final int MIN_JWT_SECRET_BYTES = 32;
    private static final int EXACT_AES_256_BYTES = 32;
    private static final int MIN_SHARED_SECRET_BYTES = 16;

    /**
     * 严禁在任何真实运行态接受仓库 .env.example 或历史上公开过的占位值
     */
    private static final Set<String> FORBIDDEN_EXACT_PLACEHOLDERS = Set.of(
            "0123456789abcdef0123456789abcdef",
            "starshow-dev-jwt-secret-key-please-change-in-production-32bytes",
            "starshow-dev-idcard-hmac-pepper-change-me",
            "starshow-dev-internal-gateway-secret",
            "starshow_dev_pass",
            "starshow_redis_pass"
    );

    private static final Set<String> FORBIDDEN_PLACEHOLDER_MARKERS = Set.of(
            "change-me",
            "change_me",
            "please-change",
            "replace-with",
            "placeholder",
            "starshow-dev-"
    );

    private SecuritySecretValidator() {
    }

    public static void validateJwtSecret(String value) {
        validateRequiredNonPlaceholder(value, PROP_JWT_SECRET);
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException("Invalid security configuration length (require >= 32 bytes): " + PROP_JWT_SECRET);
        }
    }

    public static void validateAesKey256(String value) {
        validateRequiredNonPlaceholder(value, PROP_AES_KEY);
        if (value.getBytes(StandardCharsets.UTF_8).length != EXACT_AES_256_BYTES) {
            throw new IllegalStateException("Invalid security configuration length (require exact 32 bytes for AES-256): " + PROP_AES_KEY);
        }
    }

    public static void validateIdcardPepper(String value) {
        validateRequiredNonPlaceholder(value, PROP_IDCARD_PEPPER);
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SHARED_SECRET_BYTES) {
            throw new IllegalStateException("Invalid security configuration length (require >= 16 bytes): " + PROP_IDCARD_PEPPER);
        }
    }

    public static void validateInternalGatewaySecret(String value) {
        validateRequiredNonPlaceholder(value, PROP_INTERNAL_GATEWAY_SECRET);
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SHARED_SECRET_BYTES) {
            throw new IllegalStateException("Invalid security configuration length (require >= 16 bytes): " + PROP_INTERNAL_GATEWAY_SECRET);
        }
    }

    private static void validateRequiredNonPlaceholder(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required security configuration: " + propertyName);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_EXACT_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException("Public placeholder is forbidden for security configuration: " + propertyName);
        }
        for (String marker : FORBIDDEN_PLACEHOLDER_MARKERS) {
            if (normalized.contains(marker)) {
                throw new IllegalStateException("Public placeholder is forbidden for security configuration: " + propertyName);
            }
        }
    }
}
