package com.starshow.show.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 演出域定时任务调度开关（独立分布式启动与聚合态共用）
 */
@Configuration
@EnableScheduling
public class ShowSchedulingConfig {
}
