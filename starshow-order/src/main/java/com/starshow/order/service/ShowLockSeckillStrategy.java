package com.starshow.order.service;

import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.OrderMetrics;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mq.OrderTimeoutProducer;
import com.starshow.order.vo.SeckillOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * V2 策略：Redisson 场次级分布式锁（starshow:lock:show:{showId}）覆盖同一 DB 事务，事务提交后才释放锁。
 * 严格隔离：仅操作 MySQL 物理库存与座位表，绝不污染 V4 Redis 实时库存与 Bitmap。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowLockSeckillStrategy implements SeckillStrategy {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final OrderPersistenceService persistenceService;
    private final OrderTimeoutProducer orderTimeoutProducer;
    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;
    private final OrderMetrics metrics;

    @Override
    public String name() {
        return "V2";
    }

    @Override
    public SeckillOrderVO execute(SeckillExecutionContext ctx) {
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        if (redisson == null) {
            throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED, "分布式锁客户端未就绪");
        }
        String lockKey = RedisKeys.showLock(ctx.getRequest().getShowId());
        RLock lock = redisson.getLock(lockKey);
        boolean locked;
        try {
            locked = lock.tryLock(properties.getLockWaitMs(), properties.getLockLeaseMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED);
        }
        if (!locked) {
            throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED);
        }

        OrderPersistenceService.SyncOrderPersistResult res;
        try {
            OrderPersistenceService.SyncOrderCommand cmd = new OrderPersistenceService.SyncOrderCommand(
                    ctx.getOrderNo(),
                    ctx.getUserId(),
                    ctx.getRequest().getShowId(),
                    ctx.getRequest().getTierId(),
                    ctx.isManualSeat() ? ctx.getRequest().getZoneId().trim() : ctx.getTier().getZoneId(),
                    ctx.getNormalizedSeats(),
                    ctx.getRequest().getQty(),
                    ctx.getTotalAmountCent(),
                    ctx.getRequest().getClientRequestId(),
                    ctx.getRequestFingerprint(),
                    ctx.getAuthoritativeConfigVersion(),
                    ctx.getAuthoritativeLayoutVersion(),
                    name()
            );
            // 事务提交完全结束后才在 finally 中释放锁
            res = persistenceService.persistSyncOrder(cmd, false);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ex) {
                    log.warn("Failed to unlock V2 showLock {}: {}", lockKey, ex.getMessage());
                }
            }
        }

        OrderDO order = res.order();
        metrics.recordAccepted(name());
        redisScripts.consumeSeckillToken(ctx.getUserId(), order.getShowId(), ctx.getRequest().getSeckillToken());
        redisScripts.updateAttemptAndReqStatus(
                order.getShowId(), order.getUserId(), ctx.getRequest().getClientRequestId(),
                order.getOrderNo(), order.getStatus(), "", false, properties.getRequestTtlSeconds()
        );
        if (res.closeAction() != null) {
            orderTimeoutProducer.dispatchCloseCheckAction(res.closeAction());
        }
        return SeckillOrderVO.builder()
                .orderNo(order.getOrderNo())
                .showId(order.getShowId())
                .tierId(order.getTierId())
                .qty(order.getQty())
                .amountCent(order.getAmountCent())
                .status(order.getStatus())
                .strategy(name())
                .expireTime(order.getExpireTime().atZone(ZoneId.systemDefault()).toOffsetDateTime())
                .build();
    }
}
