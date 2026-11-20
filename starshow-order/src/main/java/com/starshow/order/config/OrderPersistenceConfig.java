package com.starshow.order.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 订单域 MyBatis Mapper 扫描配置（monolith 与 distributed 双形态共享生效）
 */
@Configuration
@MapperScan("com.starshow.order.mapper")
public class OrderPersistenceConfig {
}
