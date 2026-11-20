package com.starshow.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链路一：预约蓄水、有界计数收敛与 RocketMQ 5.x 延迟提醒配置属性
 */
@Data
@ConfigurationProperties(prefix = "starshow.reservation")
public class ReservationProperties {

    /**
     * 开票前提前提醒秒数（默认 10 分钟 = 600 秒）
     */
    private long remindLeadSeconds = 600L;

    /**
     * RocketMQ 时间轮最大允许延迟秒数（与 docker/rocketmq/broker.conf timerMaxDelaySec=259200 对齐）
     */
    private long remindMaxDelaySeconds = 259200L;

    /**
     * 集成测试专用短延迟覆盖（毫秒，0 表示关闭，使用真实开票前提醒时间计算）
     */
    private long testOverrideDelayMs = 0L;

    /**
     * RocketMQ 消费/发送开关（纯单元测试环境可置 false）
     */
    private boolean mqEnabled = true;

    /**
     * 定时补偿恢复开关（默认开启，负责超 72h 入窗补投与失败重试）
     */
    private boolean recoveryEnabled = true;

    /**
     * 定时补偿扫描间隔（毫秒，默认 60 秒）
     */
    private long remindRecoveryIntervalMs = 60000L;

    /**
     * 失败或元数据临时不可用时的重试退避秒数（默认 30 秒，防止单条失败占满队列）
     */
    private long remindRetryBackoffSeconds = 30L;

    /**
     * 单次定时调度最大连续推进批次数（防止超窗或积压队列阻塞到期提醒）
     */
    private int remindRecoveryMaxBatchesPerRun = 20;

    /**
     * 单批补偿处理条数（默认 100）
     */
    private int remindRecoveryBatchSize = 100;

    /**
     * 预约计数缓存有界 TTL（秒，默认 30 秒）：
     * CAS-INCR 不续期 TTL，确保即便写入进程在 DB 提交后、Redis 更新前异常中断或切换流量，
     * 计数也会在 <= countCacheTtlSeconds 内自动回源 MySQL 收敛（ISSUE-008）。
     */
    private long countCacheTtlSeconds = 30L;

    /**
     * 预约计数重建分布式锁租约时长（秒，默认 10 秒，配合 Fencing Token 防旧锁覆盖新快照）
     */
    private long countRebuildLockLeaseSeconds = 10L;

    /**
     * 进程内计数热点微快照生存时长（毫秒，默认 0 表示默认直读 Redis；压测/高并发可开启如 1000ms）
     */
    private long countLocalSnapshotTtlMs = 0L;

    /**
     * 当 Redis 与 DB 瞬时双抖动时允许降级返回进程内陈旧快照的最大容忍秒数（默认 120 秒；超限则返回 countAvailable=false 暂隐人数）
     */
    private long countMaxStaleSeconds = 120L;

    /**
     * 开票前提醒分批错峰抖动窗口（秒，默认 0 精确对齐测试；压测与生产可配如 60s 在开票前平滑送达，购票入口仍统一在开售时间开放）
     */
    private long remindDispatchJitterSeconds = 0L;

    /**
     * 单场次布隆过滤器预期人数规模
     */
    private long bloomExpectedInsertions = 500_000L;

    /**
     * 布隆过滤器误判率（命中后强制回查 DB，保障 0 误杀）
     */
    private double bloomFalseProbability = 0.01;
}
