package com.starshow.user.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户域密码编码器、预约配置与补偿调度注册（仅引入轻量 spring-security-crypto 的 BCrypt，不引入 Web Security 链）
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReservationProperties.class)
public class UserSecurityConfig {

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
