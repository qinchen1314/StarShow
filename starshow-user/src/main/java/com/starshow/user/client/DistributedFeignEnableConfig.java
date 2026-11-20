package com.starshow.user.client;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 分布式模式 OpenFeign 扫描开关（ISSUE-005）：
 * 严格限定 @Profile("distributed")，单进程 monolith 模式下绝不注册任何 Feign 客户端。
 */
@Configuration
@Profile("distributed")
@EnableFeignClients(basePackages = "com.starshow.user.client")
public class DistributedFeignEnableConfig {
}
