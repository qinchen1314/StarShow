package com.starshow.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单域定时调度配置（超时关单任务投递与悬挂扣减巡检）
 */
@Configuration
@EnableScheduling
public class OrderSchedulingConfig {
}
