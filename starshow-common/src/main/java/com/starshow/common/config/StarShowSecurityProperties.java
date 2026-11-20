package com.starshow.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * StarShow 安全配置属性：
 * 所有敏感密钥均严禁提供公开默认值，必须通过环境变量注入并由各服务启动门禁校验。
 */
@Data
@ConfigurationProperties(prefix = "starshow.security")
public class StarShowSecurityProperties {

    /**
     * JWT HS256 签名密钥（UTF-8 长度必须 >= 32 字节，无默认值）
     */
    private String jwtSecret;

    /**
     * JWT 有效期（秒），默认 24 小时
     */
    private long jwtExpireSeconds = 86400L;

    /**
     * 实名信息 AES-256-GCM 密钥（UTF-8 长度必须恰为 32 字节，无默认值）
     */
    private String aesKey;

    /**
     * 身份证 HMAC-SHA256 服务端防彩虹表 Pepper（无默认值）
     */
    private String idcardPepper;

    /**
     * 网关与下游服务之间的内部鉴权信任密钥（防客户端伪造 X-User-Id，无默认值）
     */
    private String internalGatewaySecret;

    /**
     * Redis JWT 黑名单查询超时时间（毫秒）
     */
    private long blacklistTimeoutMs = 500L;
}
