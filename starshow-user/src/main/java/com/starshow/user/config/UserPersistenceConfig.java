package com.starshow.user.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 用户域持久层配置（独立于 @SpringBootApplication，兼容 starshow-boot-all 聚合模式）
 */
@Configuration
@MapperScan("com.starshow.user.mapper")
public class UserPersistenceConfig {
}
