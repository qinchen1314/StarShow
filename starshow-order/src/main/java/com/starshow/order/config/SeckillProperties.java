package com.starshow.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 链路三（抢票与异步下单引擎）统一配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "starshow.seckill")
public class SeckillProperties {

    /** 抢票策略：V1 | V2 | V3 | V4（每场独立售卖/压测前固定） */
    private String strategy = "V4";

    /** 短期抢票凭证 TTL（秒） */
    private long tokenTtlSeconds = 60L;

    /** 请求幂等绑定与在途记录 TTL（秒，不得短于已受理业务恢复窗口） */
    private long requestTtlSeconds = 1800L;

    /** 单次提交租约时长（毫秒） */
    private long requestLeaseMs = 15000L;

    /** 前端轮询初始间隔（毫秒） */
    private long pollInitialMs = 500L;

    /** 前端轮询最大退避间隔（毫秒） */
    private long pollMaxMs = 3000L;

    /** 前端轮询最大次数 */
    private int pollMaxAttempts = 30;

    /** 预扣凭证与防悬挂墓碑 TTL（秒，默认 24h） */
    private long deductProofTtlSeconds = 86400L;

    /** 悬挂扣减最小年龄阈值（秒，默认 5 分钟） */
    private long pendingMinAgeSeconds = 300L;

    /** 订单待支付超时时间（分钟，默认 15 分钟） */
    private long orderExpireMinutes = 15L;

    /** V2/V3 分布式锁等待超时（毫秒） */
    private long lockWaitMs = 3000L;

    /** V2/V3 分布式锁租约（毫秒） */
    private long lockLeaseMs = 10000L;

    /** V4 消费端总并发线程数 */
    private int orderConsumerConcurrency = 4;

    /** V4 消费端每票档并发限流上限（防止 InnoDB 单行热点锁拖垮 DB） */
    private int perTierConsumerConcurrency = 1;

    /** 是否启用链路三 RocketMQ 事务消息与消费 */
    private boolean mqEnabled = true;

    /** 是否启用超时关单延迟任务后台调度器 */
    private boolean timeoutDispatchEnabled = true;

    /** 超时关单延迟任务调度间隔（毫秒） */
    private long timeoutDispatchIntervalMs = 10000L;

    /** 是否启用悬挂扣减巡检调度器 */
    private boolean pendingReconcileEnabled = true;

    /** 悬挂扣减巡检间隔（毫秒，默认 5 分钟） */
    private long pendingReconcileIntervalMs = 300000L;

    public String normalizedStrategy() {
        if (strategy == null || strategy.isBlank()) {
            return "V4";
        }
        return strategy.trim().toUpperCase();
    }
}
