package com.starshow.order.client;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 仅在 distributed 分布式微服务模式下启用 Order 侧 OpenFeign 客户端，
 * 确保 monolith 单进程模式下直接注入本地 UserFacade / ShowReadFacade Bean，零 HTTP 自调用。
 */
@Configuration
@Profile("distributed")
@EnableFeignClients(basePackageClasses = {OrderUserFeignClient.class, OrderShowFeignClient.class})
public class OrderDistributedFeignEnableConfig {
}
