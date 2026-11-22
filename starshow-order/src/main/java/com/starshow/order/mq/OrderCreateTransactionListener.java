package com.starshow.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.event.OrderCreateEvent;
import com.starshow.order.config.OrderMetrics;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.lua.OrderRedisScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 链路三 V4 RocketMQ 5.x 事务消息本地事务与原子回查监听器：
 * 1. executeLocalTransaction：在 Half 消息成功后原子执行 seckill_deduct.lua / seckill_seat_deduct.lua；
 *    返回 1 -> COMMIT；返回 -1/-2/-3/-4/-5/-6 -> ROLLBACK；Redis 异常 -> UNKNOWN；
 * 2. checkLocalTransaction：执行 seckill_tx_check.lua 原子查 deduct_log，存在返回 COMMIT，
 *    不存在则在同脚本内 SET rollback_done 1 NX EX 86400 立墓碑后返回 ROLLBACK；
 *    Redis 瞬断/异常返回 UNKNOWN 交由 Broker 后续继续回查，绝不误回滚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "starshow.seckill", name = "mq-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQTransactionListener
public class OrderCreateTransactionListener implements RocketMQLocalTransactionListener {

    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;
    private final ObjectMapper objectMapper;
    private final OrderMetrics metrics;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        if (!(arg instanceof OrderCreateTxContext ctx) || ctx.getEvent() == null) {
            log.error("Missing OrderCreateTxContext in executeLocalTransaction");
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        OrderCreateEvent event = ctx.getEvent();
        long nowMs = System.currentTimeMillis();
        long nowSec = event.getDeductEpochSec() != null ? event.getDeductEpochSec() : (nowMs / 1000L);
        long ttlSec = Math.max(60L, properties.getDeductProofTtlSeconds());
        int expectedVersion = ctx.getExpectedSaleConfigVersion() > 0 ? ctx.getExpectedSaleConfigVersion() : 1;

        try {
            long code;
            if (ctx.isManualSeat()) {
                OrderRedisScripts.SeatDeductResult res = redisScripts.executeSeatDeduct(
                        event.getShowId(),
                        event.getTierId(),
                        event.getZoneId(),
                        event.getUserId(),
                        event.getOrderNo(),
                        event.getSeatIndices(),
                        nowSec,
                        ttlSec,
                        expectedVersion,
                        nowMs,
                        ctx.getLeaseVersion()
                );
                code = res.code();
                ctx.setConflictSeatIndices(res.conflictSeatIndices());
            } else {
                code = redisScripts.executeAutoDeduct(
                        event.getShowId(),
                        event.getTierId(),
                        event.getUserId(),
                        event.getQty(),
                        event.getOrderNo(),
                        nowSec,
                        ttlSec,
                        expectedVersion,
                        nowMs,
                        ctx.getLeaseVersion()
                );
            }
            ctx.setLuaCode(code);

            if (code == 1L) {
                if (ctx.isSimulateUnknownAfterDeduct()) {
                    log.warn("[FAULT_INJECT] Simulating COMMIT packet drop (returning UNKNOWN) after Lua deduct success: orderNo={}",
                            event.getOrderNo());
                    return RocketMQLocalTransactionState.UNKNOWN;
                }
                return RocketMQLocalTransactionState.COMMIT;
            }
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception ex) {
            ctx.setLocalException(ex);
            log.error("Redis error during V4 executeLocalTransaction, returning UNKNOWN for Broker check: orderNo={}",
                    event.getOrderNo(), ex);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        OrderCreateEvent event = extractEvent(msg);
        if (event == null || event.getShowId() == null || event.getOrderNo() == null) {
            log.error("Cannot parse OrderCreateEvent during checkLocalTransaction: {}", msg);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        return checkByShowAndOrder(event.getShowId(), event.getOrderNo());
    }

    /**
     * 抽取供真实 Broker 回查与 L1/L3 测试直接验证的原子回查核心
     */
    public RocketMQLocalTransactionState checkByShowAndOrder(long showId, long orderNo) {
        long ttlSec = Math.max(60L, properties.getDeductProofTtlSeconds());
        try {
            long checkResult = redisScripts.executeTxCheck(showId, orderNo, ttlSec);
            if (checkResult == 1L) {
                metrics.recordTxCheckCommit();
                log.info("Broker checkLocalTransaction -> COMMIT (deduct_log exists): showId={}, orderNo={}", showId, orderNo);
                return RocketMQLocalTransactionState.COMMIT;
            }
            metrics.recordTxCheckRollback();
            log.info("Broker checkLocalTransaction -> ROLLBACK (tombstone written atomically): showId={}, orderNo={}", showId, orderNo);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception ex) {
            log.warn("Redis transient failure during checkLocalTransaction, returning UNKNOWN: showId={}, orderNo={}, err={}",
                    showId, orderNo, ex.getMessage());
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private OrderCreateEvent extractEvent(Message msg) {
        if (msg == null || msg.getPayload() == null) {
            return null;
        }
        Object payload = msg.getPayload();
        if (payload instanceof OrderCreateEvent event) {
            return event;
        }
        try {
            if (payload instanceof byte[] bytes) {
                return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), OrderCreateEvent.class);
            }
            if (payload instanceof String json) {
                return objectMapper.readValue(json, OrderCreateEvent.class);
            }
            return objectMapper.convertValue(payload, OrderCreateEvent.class);
        } catch (Exception ex) {
            log.error("Failed to deserialize OrderCreateEvent in checkLocalTransaction", ex);
            return null;
        }
    }
}
