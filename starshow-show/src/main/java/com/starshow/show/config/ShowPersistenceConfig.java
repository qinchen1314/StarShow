package com.starshow.show.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 演出域持久层配置：
 * 独立配置类标注 @MapperScan，避免被 StarShowBootAllApplication 的 @SpringBootApplication 排除过滤漏扫。
 */
@Configuration
@MapperScan("com.starshow.show.mapper")
public class ShowPersistenceConfig {
}
