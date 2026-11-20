package com.starshow.common.auth;

/**
 * JWT 黑名单检查 SPI：
 * 抽象在 starshow-common 中，避免公共模块引入重 Redis 依赖，
 * 由业务模块（如 starshow-user）提供基于 Redis 的实现并注入。
 */
@FunctionalInterface
public interface TokenBlacklistChecker {

    /**
     * 判断指定 JWT ID (jti) 是否已被拉黑（主动登出或被踢出）
     */
    boolean isBlacklisted(String jti);
}
