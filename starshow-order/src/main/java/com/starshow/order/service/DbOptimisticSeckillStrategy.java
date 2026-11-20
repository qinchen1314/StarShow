package com.starshow.order.service;

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
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * V1 基线策略：MySQL 乐观锁（WHERE version = ? AND available_stock >= qty）
 * 严格隔离：仅操作 MySQL 物理库存与座位表，绝不污染 V4 Redis 实时库存与 Bitmap。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbOptimisticSeckillStrategy implements SeckillStrategy {

    private static final int MAX_OPTIMISTIC_RETRIES = 12;

    private final OrderPersistenceService persistenceService;
    private final OrderTimeoutProducer orderTimeoutProducer;
    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;
    private final OrderMetrics metrics;

    @Override
    public String name() {
        return "V1";
    }

    @Override
    public SeckillOrderVO execute(SeckillExecutionContext ctx) {
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

        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            OrderPersistenceService.SyncOrderPersistResult res = persistenceService.persistSyncOrder(cmd, true);
            if (!res.optimisticConflict() && res.order() != null) {
                return onSyncSuccess(ctx, res);
            }
            // 乐观锁版本冲突：短暂退避后重试
            try {
                Thread.sleep(Math.min(15L, attempt * 2L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED);
            }
        }
        throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED, "高并发版本冲突过多，请稍后重试");
    }

    private SeckillOrderVO onSyncSuccess(SeckillExecutionContext ctx, OrderPersistenceService.SyncOrderPersistResult res) {
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
