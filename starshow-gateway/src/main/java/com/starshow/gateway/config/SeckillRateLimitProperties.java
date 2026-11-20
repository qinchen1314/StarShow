package com.starshow.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关抢票写路径限流配置（用户维度 + 场次维度）
 */
@Data
@Component
@ConfigurationProperties(prefix = "starshow.gateway.seckill-rate-limit")
public class SeckillRateLimitProperties {

    /** 是否开启网关抢票写路径限流 */
    private boolean enabled = true;

    /** 单用户在窗口期内允许的抢票写请求次数（默认每秒 20 次） */
    private long userLimit = 20L;

    /** 单场次在窗口期内允许的抢票写请求次数（默认每秒 5000 次） */
    private long showLimit = 5000L;

    /** 限流时间窗口秒数（默认 1 秒） */
    private long windowSeconds = 1L;

    /** Redis 限流检查超时（毫秒） */
    private long redisTimeoutMs = 300L;
}
