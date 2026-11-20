package com.starshow.common.config;

import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 公共基础组件自动装配（Servlet 与 WebFlux 均安全可用，零 Servlet 依赖）
 */
@AutoConfiguration
@EnableConfigurationProperties(StarShowSecurityProperties.class)
public class StarShowCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GeneSnowflakeIdGenerator geneSnowflakeIdGenerator(
            @Value("${starshow.worker-id:1}") long workerId) {
        return new GeneSnowflakeIdGenerator(workerId);
    }

    /**
     * 仅在显式配置了 starshow.security.jwt-secret 时装配（不需要 JWT 的独立下游如 starshow-show 不强制要求该配置；
     * 需要 JWT 的 gateway / user / boot-all 通过各自启动校验器强制要求非空且非公开占位值）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starshow.security", name = "jwt-secret")
    public JwtTokenProvider jwtTokenProvider(StarShowSecurityProperties properties) {
        return new JwtTokenProvider(properties.getJwtSecret(), properties.getJwtExpireSeconds());
    }
}
