package com.starshow.order.service;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.event.OrderCreateEvent;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.order.config.OrderMetrics;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mq.OrderCreateTxContext;
import com.starshow.order.vo.SeckillOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V4 终版策略：RocketMQ 5.x Half 事务消息 + {showId} 单槽 Lua 原子预扣/锁座 + 原子回查立墓碑防悬挂
 * - 严格顺序：Half -> Lua -> COMMIT/ROLLBACK；
 * - 仅当确认 COMMIT 时返回 PENDING_CONFIRM（Controller 输出 HTTP 202/00202）；
 * - 发送确认未知返回 HTTP 503/B0103 并带已绑定的原 orderNo；
 * - 售罄（-1）与座位冲突（-4）快速失败时不删除 seckillToken，清理未生效绑定允许立即换档/换座重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuaMqSeckillStrategy implements SeckillStrategy {

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;
    private final OrderMetrics metrics;

    @Override
    public String name() {
        return "V4";
    }

    @Override
    public SeckillOrderVO execute(SeckillExecutionContext ctx) {
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (!properties.isMqEnabled() || rocketMQTemplate == null) {
            throw buildSendUnknownException(ctx, "下单消息服务暂不可用，请稍后查询原单状态");
        }

        long nowSec = System.currentTimeMillis() / 1000L;
        String msgKey = "order-create:" + ctx.getOrderNo();
        String zoneId = ctx.isManualSeat() ? ctx.getRequest().getZoneId().trim() : ctx.getTier().getZoneId();

        OrderCreateEvent event = OrderCreateEvent.builder()
                .msgKey(msgKey)
                .orderNo(ctx.getOrderNo())
                .userId(ctx.getUserId())
                .showId(ctx.getRequest().getShowId())
                .tierId(ctx.getRequest().getTierId())
                .zoneId(zoneId)
                .seatIndices(ctx.isManualSeat() ? ctx.getNormalizedSeats() : null)
                .qty(ctx.getRequest().getQty())
                .amountCent(ctx.getTotalAmountCent())
                .deductEpochSec(nowSec)
                .clientRequestId(ctx.getRequest().getClientRequestId().trim())
                .requestFingerprint(ctx.getRequestFingerprint())
                .saleConfigVersion(ctx.getAuthoritativeConfigVersion())
                .layoutVersion(ctx.getAuthoritativeLayoutVersion())
                .strategy(name())
                .traceId(TraceIdContext.getOrCreate())
                .build();

        OrderCreateTxContext txContext = OrderCreateTxContext.builder()
                .event(event)
                .leaseVersion(ctx.getLeaseVersion())
                .manualSeat(ctx.isManualSeat())
                .expectedSaleConfigVersion(ctx.getAuthoritativeConfigVersion())
                .layoutVersion(ctx.getAuthoritativeLayoutVersion())
                .simulateUnknownAfterDeduct(ctx.isSimulateUnknownAfterDeduct())
                .build();

        Message<OrderCreateEvent> message = MessageBuilder.withPayload(event)
                .setHeader(RocketMQHeaders.KEYS, msgKey)
                .build();

        TransactionSendResult sendResult;
        try {
            sendResult = rocketMQTemplate.sendMessageInTransaction(MqTopics.ORDER_CREATE, message, txContext);
        } catch (Exception ex) {
            log.error("RocketMQ sendMessageInTransaction failed/unknown for orderNo={}: {}", ctx.getOrderNo(), ex.getMessage(), ex);
            throw buildSendUnknownException(ctx, "下单消息投递结果暂未确认，请凭原单号查询办理进度");
        }

        if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw buildSendUnknownException(ctx, "下单消息投递未确认，请凭原单号查询办理进度");
        }

        LocalTransactionState localState = sendResult.getLocalTransactionState();
        if (localState == LocalTransactionState.COMMIT_MESSAGE) {
            metrics.recordAccepted(name());
            redisScripts.consumeSeckillToken(ctx.getUserId(), ctx.getRequest().getShowId(), ctx.getRequest().getSeckillToken());
            redisScripts.updateAttemptAndReqStatus(
                    ctx.getRequest().getShowId(),
                    ctx.getUserId(),
                    ctx.getRequest().getClientRequestId(),
                    ctx.getOrderNo(),
                    "PENDING_CONFIRM",
                    "",
                    false,
                    properties.getRequestTtlSeconds()
            );
            return SeckillOrderVO.builder()
                    .orderNo(ctx.getOrderNo())
                    .showId(ctx.getRequest().getShowId())
                    .tierId(ctx.getRequest().getTierId())
                    .qty(ctx.getRequest().getQty())
                    .amountCent(ctx.getTotalAmountCent())
                    .status("PENDING_CONFIRM")
                    .strategy(name())
                    .pollIntervalMs(properties.getPollInitialMs())
                    .build();
        }

        if (localState == LocalTransactionState.ROLLBACK_MESSAGE) {
            handleLuaRollbackFailure(ctx, txContext);
        }

        // UNKNOW：保留原单号与绑定，返回 HTTP 503/B0103 + 原单号交由轮询或 Broker 回查收敛
        throw buildSendUnknownException(ctx, "下单预扣状态确认中，请凭原单号查询进度");
    }

    private void handleLuaRollbackFailure(SeckillExecutionContext ctx, OrderCreateTxContext txContext) {
        long showId = ctx.getRequest().getShowId();
        long userId = ctx.getUserId();
        long orderNo = ctx.getOrderNo();
        String clientReqId = ctx.getRequest().getClientRequestId().trim();
        long nowMs = System.currentTimeMillis();
        long reqTtl = properties.getRequestTtlSeconds();

        long code = txContext.getLuaCode() == null ? -5L : txContext.getLuaCode();
        switch ((int) code) {
            case -1 -> {
                metrics.recordStockShortage();
                // 售罄快速失败：释放未生效 req 绑定且保留 seckillToken，允许用户立刻换档重试
                redisScripts.releaseRequestBinding(showId, userId, clientReqId, orderNo,
                        ErrorCode.STOCK_NOT_ENOUGH.getCode(), false, true, nowMs, reqTtl, ctx.getLeaseVersion());
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
            }
            case -4 -> {
                metrics.recordSeatConflict();
                // 座位冲突快速失败：释放未生效 req 绑定且保留 seckillToken，回传冲突偏移
                redisScripts.releaseRequestBinding(showId, userId, clientReqId, orderNo,
                        ErrorCode.SEAT_CONFLICT.getCode(), false, true, nowMs, reqTtl, ctx.getLeaseVersion());
                throw BizException.withData(
                        ErrorCode.SEAT_CONFLICT,
                        ErrorCode.SEAT_CONFLICT.getMessage(),
                        OrderPersistenceService.buildSeatConflictData(
                                showId,
                                ctx.getRequest().getZoneId(),
                                ctx.getAuthoritativeLayoutVersion(),
                                txContext.safeConflictSeats()
                        )
                );
            }
            case -2 -> {
                metrics.recordDuplicateRejected();
                redisScripts.releaseRequestBinding(showId, userId, clientReqId, orderNo,
                        ErrorCode.DUPLICATE_PURCHASE.getCode(), false, false, nowMs, reqTtl, ctx.getLeaseVersion());
                throw new BizException(ErrorCode.DUPLICATE_PURCHASE);
            }
            case -3 -> {
                redisScripts.releaseRequestBinding(showId, userId, clientReqId, orderNo,
                        ErrorCode.DEDUCT_REJECTED_BY_TOMBSTONE.getCode(), false, true, nowMs, reqTtl, ctx.getLeaseVersion());
                throw new BizException(ErrorCode.DEDUCT_REJECTED_BY_TOMBSTONE);
            }
            case -5 -> {
                redisScripts.releaseRequestBinding(showId, userId, clientReqId, orderNo,
                        ErrorCode.STOCK_UNAVAILABLE.getCode(), false, true, nowMs, reqTtl, ctx.getLeaseVersion());
                throw BizException.serviceUnavailable(ErrorCode.STOCK_UNAVAILABLE, 1000L, "在售库存或座位热数据暂不可用，请稍后刷新");
            }
            case -6 -> {
                redisScripts.releaseRequestBinding(showId, userId, clientReqId, orderNo,
                        ErrorCode.SHOW_NOT_ON_SALE.getCode(), false, true, nowMs, reqTtl, ctx.getLeaseVersion());
                throw resolveGateRejection(ctx);
            }
            default -> throw buildSendUnknownException(ctx, "下单请求仍在确认，请凭原单号查询进度");
        }
    }

    /**
     * 按权威 Redis 门禁状态精准分流 -6 错误（A0205 版本变化 / B0201 在售热数据丢失 / A0202 未开售或停售）
     */
    private BizException resolveGateRejection(SeckillExecutionContext ctx) {
        OrderRedisScripts.ReadyGateSnapshot gate = redisScripts.readReadyGate(ctx.getRequest().getShowId());
        if (gate == null) {
            String saleStatus = ctx.getTier().getSaleStatus();
            if ("ON_SALE".equalsIgnoreCase(saleStatus) || "SOLD_OUT".equalsIgnoreCase(saleStatus)) {
                return BizException.serviceUnavailable(ErrorCode.STOCK_UNAVAILABLE, 1000L, "售卖期门禁热数据暂不可用，请稍后刷新");
            }
            return new BizException(ErrorCode.SHOW_NOT_ON_SALE, "演出尚未完成预热或暂未开票");
        }
        if (gate.getVersion() != null && gate.getVersion() != ctx.getAuthoritativeConfigVersion()) {
            return new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, "开售配置版本已变更，请刷新演出详情");
        }
        return new BizException(ErrorCode.SHOW_NOT_ON_SALE, "当前不在演出开售时间窗口内或已停售");
    }

    private BizException buildSendUnknownException(SeckillExecutionContext ctx, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", String.valueOf(ctx.getOrderNo()));
        data.put("showId", String.valueOf(ctx.getRequest().getShowId()));
        data.put("status", "PENDING_CONFIRM");
        data.put("pollIntervalMs", properties.getPollInitialMs());
        return BizException.serviceUnavailableWithData(ErrorCode.MQ_SEND_FAILED, 1000L, detail, data);
    }
}
