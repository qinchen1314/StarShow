package com.starshow.show.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 链路二演出浏览、多级缓存、座位图、预热与生命周期配置项（前缀 starshow.show）
 */
@Data
@Component
@ConfigurationProperties(prefix = "starshow.show")
public class ShowProperties {

    /**
     * L1 Caffeine 详情缓存写入后 TTL（秒）
     */
    private long detailL1TtlSeconds = 60L;

    /**
     * L1 Caffeine 详情缓存最大条目数
     */
    private long detailL1MaxSize = 5000L;

    /**
     * L2 Redis 详情基础逻辑过期时长（秒）
     */
    private long detailL2LogicalTtlSeconds = 300L;

    /**
     * L2 Redis 详情逻辑过期随机抖动上限（秒，0..jitter）
     */
    private long detailL2JitterSeconds = 300L;

    /**
     * 允许返回过期陈旧详情快照的最大宽限时长（秒，超出后拒绝当现值）
     */
    private long detailMaxStaleSeconds = 900L;

    /**
     * 确认不存在的场次空值缓存 TTL（秒）
     */
    private long negativeCacheTtlSeconds = 30L;

    /**
     * 冷 miss 合并回源等待超时上限（毫秒），超时返回 503/B9999
     */
    private long coldReadTimeoutMs = 150L;

    /**
     * 冷 miss 或 Redis 故障时的最大并发回源 DB 许可数
     */
    private int coldReadMaxConcurrent = 32;

    /**
     * 异步逻辑过期重建线程池最大任务数与队列容量
     */
    private int rebuildThreadPoolSize = 4;
    private int rebuildQueueCapacity = 128;

    /**
     * 详情逻辑过期重建分布式互斥锁租约（秒）
     */
    private long rebuildLockLeaseSeconds = 10L;

    /**
     * 热点票档库存本地短快照 TTL（毫秒，默认 0 直读 Redis；可配 500~1000）
     */
    private long stockLocalTtlMs = 0L;

    /**
     * 预热每批处理的票档/分区数
     */
    private int preheatBatchSize = 50;

    /**
     * 预热任务执行租约时长（秒），超时未续期视为崩溃可被新 executionId 抢占续跑
     */
    private long preheatLeaseSeconds = 30L;

    /**
     * 场次操作锁等待与租约时长（毫秒）
     */
    private long showOpLockWaitMs = 3000L;
    private long showOpLockLeaseMs = 15000L;

    /**
     * 是否启用预热/改期同步任务恢复调度
     */
    private boolean taskRecoveryEnabled = true;

    /**
     * 任务恢复轮询间隔（毫秒）与单批大小
     */
    private long taskRecoveryIntervalMs = 10000L;
    private int taskRecoveryBatchSize = 100;

    /**
     * 是否启用到点开售/停售异步状态推进调度
     */
    private boolean lifecycleSyncEnabled = true;
    private long lifecycleSyncIntervalMs = 5000L;

    /**
     * 有效场次目录布隆防穿透配置
     */
    private boolean bloomEnabled = true;
    private long bloomExpectedInsertions = 100_000L;
    private double bloomFalseProbability = 0.01D;
    private long bloomSyncIntervalMs = 30000L;

    /**
     * 单笔订单最大购买张数（用于 TierSnapshotDTO 下单契约）
     */
    private int maxTicketsPerOrder = 4;

    /**
     * 503 不可用响应建议客户端重试等待毫秒数
     */
    private long retryAfterMs = 1000L;
}
