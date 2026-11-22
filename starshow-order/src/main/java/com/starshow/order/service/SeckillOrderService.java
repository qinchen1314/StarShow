package com.starshow.order.service;

import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.dto.ZoneSeatSpecDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.dto.SeckillOrderRequest;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.vo.SeckillOrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 链路三核心下单编排服务（V1~V4 统一入口）：
 * 1. 先检查本人是否已有同 clientRequestId 的持久订单或 Redis 绑定：
 *    - 摘要不一致拒绝 A0307；
 *    - 摘要一致直接找回原单号与状态（不受后续票价变化、窗口变化与 Token 失效影响）；
 * 2. 首次请求执行实时准入校验：实名资格（A0104）、票档快照（A0203）、配置版本（A0205）、票价确认（A0308 + 当前权威价）、
 *    限购张数与自主选座/连座走道校验；
 * 3. 执行第一段短 Lua seckill_req_bind.lua 绑定唯一胜出 orderNo；
 * 4. 分派当前配置的策略（V1/V2/V3/V4）执行；V1~V3 遇到售罄/座位冲突/锁超时快速失败时清理未生效 req 绑定并保留 Token。
 */
@Slf4j
@Service
public class SeckillOrderService {

    private final UserFacade userFacade;
    private final ShowReadFacade showReadFacade;
    private final SeatSelectionValidator seatSelectionValidator;
    private final OrderShardMapper orderShardMapper;
    private final OrderRedisScripts redisScripts;
    private final GeneSnowflakeIdGenerator idGenerator;
    private final SeckillProperties properties;
    private final Map<String, SeckillStrategy> strategiesByName;

    public SeckillOrderService(UserFacade userFacade,
                               ShowReadFacade showReadFacade,
                               SeatSelectionValidator seatSelectionValidator,
                               OrderShardMapper orderShardMapper,
                               OrderRedisScripts redisScripts,
                               GeneSnowflakeIdGenerator idGenerator,
                               SeckillProperties properties,
                               List<SeckillStrategy> strategies) {
        this.userFacade = userFacade;
        this.showReadFacade = showReadFacade;
        this.seatSelectionValidator = seatSelectionValidator;
        this.orderShardMapper = orderShardMapper;
        this.redisScripts = redisScripts;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(s -> s.name().toUpperCase(), Function.identity()));
    }

    public SeckillOrderVO placeOrder(long userId, SeckillOrderRequest request) {
        return placeOrderInternal(userId, request, null, false);
    }

    /**
     * 供 L2/L3 验收测试显式指定策略或故障注入（不影响正常生产请求路径）
     */
    public SeckillOrderVO placeOrderWithStrategyOverride(long userId, SeckillOrderRequest request,
                                                         String strategyOverride, boolean simulateUnknownAfterDeduct) {
        return placeOrderInternal(userId, request, strategyOverride, simulateUnknownAfterDeduct);
    }

    private SeckillOrderVO placeOrderInternal(long userId, SeckillOrderRequest request,
                                              String strategyOverride, boolean simulateUnknownAfterDeduct) {
        long showId = request.getShowId();
        String clientRequestId = request.getClientRequestId().trim();
        String fingerprint = SeckillRequestFingerprint.compute(userId, request);

        // 1. 优先查本人基因分片是否已存在同 clientRequestId 的持久订单（不受后续 Token 失效/票价变化/窗口关闭影响）
        String shardTable = OrderShardTableResolver.tableForUserId(userId);
        OrderDO persistedByReq = orderShardMapper.selectByUserShowRequest(shardTable, userId, showId, clientRequestId);
        if (persistedByReq != null) {
            if (persistedByReq.getRequestFingerprint() != null
                    && !persistedByReq.getRequestFingerprint().equals(fingerprint)) {
                throw new BizException(ErrorCode.REQUEST_CONFLICT);
            }
            return toOrderVOFromPersisted(persistedByReq);
        }

        // 2. 查 Redis 同请求绑定（若已存在且已受理/已落单，直接恢复原单号，不受后续 Token/票价/窗口影响）
        OrderRedisScripts.ExistingReqRecord existingReq = redisScripts.readRequestBinding(showId, userId, clientRequestId);
        String configuredStrategy = (strategyOverride != null && !strategyOverride.isBlank())
                ? strategyOverride.trim().toUpperCase()
                : properties.normalizedStrategy();
        boolean resumingV4 = existingReq != null && "V4".equals(existingReq.getStrategy());
        if (existingReq != null) {
            if (existingReq.getFingerprint() != null && !existingReq.getFingerprint().equals(fingerprint)) {
                throw new BizException(ErrorCode.REQUEST_CONFLICT);
            }
            if (existingReq.getOrderNo() != null) {
                OrderDO byOrderNo = orderShardMapper.selectByOrderNo(shardTable, existingReq.getOrderNo());
                if (byOrderNo != null) {
                    return toOrderVOFromPersisted(byOrderNo);
                }
                if ("FAILED".equals(existingReq.getStatus()) && resumingV4) {
                    throw new BizException(resolveErrorCode(existingReq.getFailCode()));
                }
                if ("PENDING_CONFIRM".equals(existingReq.getStatus())
                        || resumingV4 && redisScripts.readDeductLog(showId, existingReq.getOrderNo()) != null) {
                    return SeckillOrderVO.builder()
                            .orderNo(existingReq.getOrderNo())
                            .showId(showId)
                            .tierId(existingReq.getTierId() != null ? existingReq.getTierId() : request.getTierId())
                            .qty(existingReq.getQty() != null ? existingReq.getQty() : request.getQty())
                            .status("PENDING_CONFIRM")
                            .strategy(existingReq.getStrategy())
                            .pollIntervalMs(properties.getPollInitialMs())
                            .build();
                }
            }
        } else if ("V4".equals(configuredStrategy)) {
            Long current = redisScripts.readCurrentRequestOrderNo(showId, userId);
            if (current != null) {
                return recoverCurrentBinding(userId, showId, current);
            }
        }

        // 3. 首次请求或租约到期恢复：执行完整实时准入校验
        if (!userFacade.isRealNameVerified(userId)) {
            throw new BizException(ErrorCode.IDENTITY_REQUIRED);
        }
        TierSnapshotDTO tier = showReadFacade.getTierSnapshot(showId, request.getTierId());
        if (tier == null) {
            throw new BizException(ErrorCode.TIER_NOT_FOUND);
        }

        int authoritativeVersion = tier.effectiveSaleConfigVersion();
        if ((existingReq == null || resumingV4) && request.getSaleConfigVersion() != authoritativeVersion) {
            throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED,
                    "开售配置版本已变更，当前权威版本=" + authoritativeVersion);
        }

        // 价格防篡改与变价提醒（A0308 + 当前权威价格）
        long unitPrice = tier.getPriceCent();
        if ((existingReq == null || resumingV4) && !request.getExpectedUnitPriceCent().equals(unitPrice)) {
            Map<String, Object> priceData = new LinkedHashMap<>();
            priceData.put("showId", String.valueOf(showId));
            priceData.put("tierId", String.valueOf(tier.getTierId()));
            priceData.put("currentUnitPriceCent", String.valueOf(unitPrice));
            priceData.put("expectedUnitPriceCent", String.valueOf(request.getExpectedUnitPriceCent()));
            priceData.put("saleConfigVersion", authoritativeVersion);
            throw BizException.withData(ErrorCode.PRICE_CHANGED, ErrorCode.PRICE_CHANGED.getMessage(), priceData);
        }

        // 整数溢出安全计算总金额（单位：分）
        long totalAmountCent;
        try {
            totalAmountCent = Math.multiplyExact(unitPrice, (long) request.getQty());
        } catch (ArithmeticException ex) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单金额超出有效范围");
        }

        // 每单最大购票数量限制
        int maxPerOrder = (tier.getMaxTicketsPerOrder() == null || tier.getMaxTicketsPerOrder() < 1)
                ? 1 : tier.getMaxTicketsPerOrder();
        if (request.getQty() > maxPerOrder) {
            throw new BizException(ErrorCode.INVALID_PARAM, "单笔最多购买 " + maxPerOrder + " 张");
        }

        // 自主选座合法性与连座走道校验
        boolean manualSeat = SeatSelectionValidator.SEAT_MODE_MANUAL.equalsIgnoreCase(tier.getSeatMode());
        ZoneSeatSpecDTO zoneSpec = null;
        if (manualSeat && request.getZoneId() != null && !request.getZoneId().isBlank()) {
            zoneSpec = showReadFacade.getZoneSeatSpec(showId, request.getZoneId().trim());
        }
        List<Integer> normalizedSeats = seatSelectionValidator.validateAndNormalize(request, tier, zoneSpec);

        String strategyName = resumingV4 ? "V4" : configuredStrategy;
        SeckillStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "不支持的抢票策略: " + strategyName);
        }

        // 非 V4 策略额外做窗口状态校验（V4 门禁校验由第二段 Lua 原子兜底，此处也防未开售提前绑定）
        if (existingReq == null || resumingV4) {
            validateSaleWindowBeforeBind(tier, strategyName);
        }

        // 4. 第一段短 Lua：原子校验 seckillToken 并绑定 (userId, showId, clientRequestId) -> 唯一胜出 orderNo
        long candidateOrderNo = idGenerator.nextOrderId(userId);
        long nowMs = System.currentTimeMillis();
        OrderRedisScripts.ReqBindResult bind = redisScripts.bindRequest(
                showId,
                userId,
                clientRequestId,
                candidateOrderNo,
                fingerprint,
                request.getTierId(),
                request.getQty(),
                authoritativeVersion,
                request.getSeckillToken().trim(),
                nowMs,
                Math.max(60L, properties.getRequestTtlSeconds()),
                Math.max(1000L, properties.getRequestLeaseMs()),
                strategyName,
                existingReq == null ? null : existingReq.getOrderNo()
        );

        switch (bind.code()) {
            case "CONFLICT" -> throw new BizException(ErrorCode.REQUEST_CONFLICT);
            case "TOKEN_INVALID" -> throw new BizException(ErrorCode.SECKILL_TOKEN_INVALID);
            case "VERSION_MISMATCH" -> throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED);
            case "CURRENT" -> {
                return recoverCurrentBinding(userId, showId, bind.orderNo());
            }
            case "EXISTING" -> {
                long winningOrderNo = bind.orderNo();
                OrderDO recheck = orderShardMapper.selectByOrderNo(shardTable, winningOrderNo);
                if (recheck != null) {
                    return toOrderVOFromPersisted(recheck);
                }
                if ("PENDING_CONFIRM".equals(bind.status())) {
                    return SeckillOrderVO.builder()
                            .orderNo(winningOrderNo)
                            .showId(showId)
                            .tierId(request.getTierId())
                            .qty(request.getQty())
                            .amountCent(totalAmountCent)
                            .status("PENDING_CONFIRM")
                            .strategy(strategyName)
                            .pollIntervalMs(properties.getPollInitialMs())
                            .build();
                }
                if ("FAILED".equals(bind.status())) {
                    throw new BizException(resolveErrorCode(bind.failCode()));
                }
                if (!bind.leaseAcquired()) {
                    if ("V4".equals(strategyName)) {
                        throw submitting(showId, winningOrderNo);
                    }
                    // 原请求仍在租约内执行中：不并发二次扣减，直接返回原单处理中
                    return SeckillOrderVO.builder()
                            .orderNo(winningOrderNo)
                            .showId(showId)
                            .tierId(request.getTierId())
                            .qty(request.getQty())
                            .amountCent(totalAmountCent)
                            .status("PENDING_CONFIRM")
                            .strategy(strategyName)
                            .pollIntervalMs(properties.getPollInitialMs())
                            .build();
                }
                // 租约已到期，按唯一胜出原单号重新争取执行权
                return executeStrategy(userId, winningOrderNo, request, tier, normalizedSeats,
                        totalAmountCent, fingerprint, authoritativeVersion, manualSeat, strategy,
                        simulateUnknownAfterDeduct, candidateOrderNo);
            }
            case "BOUND" -> {
                return executeStrategy(userId, bind.orderNo(), request, tier, normalizedSeats,
                        totalAmountCent, fingerprint, authoritativeVersion, manualSeat, strategy,
                        simulateUnknownAfterDeduct, candidateOrderNo);
            }
            default -> throw new BizException(ErrorCode.SYSTEM_ERROR, "未知请求绑定状态: " + bind.code());
        }
    }

    private SeckillOrderVO recoverCurrentBinding(long userId, long showId, long orderNo) {
        OrderDO order = orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForUserId(userId), orderNo);
        if (order != null && Long.valueOf(userId).equals(order.getUserId()) && Long.valueOf(showId).equals(order.getShowId())) {
            return toOrderVOFromPersisted(order);
        }
        OrderRedisScripts.AttemptRecord attempt = redisScripts.readAttempt(showId, orderNo);
        if (attempt != null && Long.valueOf(userId).equals(attempt.getUserId())) {
            if ("FAILED".equals(attempt.getStatus())) {
                throw new BizException(resolveErrorCode(attempt.getFailCode()));
            }
            if (redisScripts.readDeductLog(showId, orderNo) != null) {
                return SeckillOrderVO.builder().orderNo(orderNo).showId(showId).tierId(attempt.getTierId())
                        .qty(attempt.getQty()).strategy("V4").status("PENDING_CONFIRM")
                        .pollIntervalMs(properties.getPollInitialMs()).build();
            }
        }
        throw submitting(showId, orderNo);
    }

    private BizException submitting(long showId, long orderNo) {
        return BizException.serviceUnavailableWithData(ErrorCode.MQ_SEND_FAILED, properties.getPollInitialMs(),
                "原请求正在提交，请凭原单号查询", Map.of("showId", Long.toString(showId),
                        "orderNo", Long.toString(orderNo), "status", "SUBMITTING",
                        "pollIntervalMs", properties.getPollInitialMs()));
    }

    private SeckillOrderVO executeStrategy(long userId, long orderNo, SeckillOrderRequest request,
                                           TierSnapshotDTO tier, List<Integer> normalizedSeats,
                                           long totalAmountCent, String fingerprint, int authoritativeVersion,
                                           boolean manualSeat, SeckillStrategy strategy, boolean simulateUnknownAfterDeduct,
                                           long leaseVersion) {
        SeckillExecutionContext ctx = SeckillExecutionContext.builder()
                .userId(userId)
                .orderNo(orderNo)
                .leaseVersion("V4".equals(strategy.name()) ? leaseVersion : null)
                .request(request)
                .tier(tier)
                .normalizedSeats(normalizedSeats)
                .totalAmountCent(totalAmountCent)
                .requestFingerprint(fingerprint)
                .authoritativeConfigVersion(authoritativeVersion)
                .authoritativeLayoutVersion(manualSeat ? tier.getLayoutVersion() : null)
                .strategyName(strategy.name())
                .manualSeat(manualSeat)
                .simulateUnknownAfterDeduct(simulateUnknownAfterDeduct)
                .build();

        try {
            return strategy.execute(ctx);
        } catch (BizException ex) {
            // V1~V3 同步策略快速失败（售罄/座位冲突/锁超时）：释放未生效 req 绑定且不消费 Token，允许用户换档重选
            if (!"V4".equals(strategy.name())) {
                boolean deleteReq = ex.getErrorCode() == ErrorCode.STOCK_NOT_ENOUGH
                        || ex.getErrorCode() == ErrorCode.SEAT_CONFLICT
                        || ex.getErrorCode() == ErrorCode.LOCK_ACQUIRE_FAILED;
                redisScripts.releaseRequestBinding(
                        request.getShowId(),
                        userId,
                        request.getClientRequestId().trim(),
                        orderNo,
                        ex.getErrorCode().getCode(),
                        deleteReq,
                        deleteReq,
                        System.currentTimeMillis(),
                        properties.getRequestTtlSeconds()
                );
            }
            throw ex;
        }
    }

    private void validateSaleWindowBeforeBind(TierSnapshotDTO tier, String strategyName) {
        long nowMs = System.currentTimeMillis();
        long startMs = tier.getSaleStartTime() == null ? 0L
                : tier.getSaleStartTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = tier.getSaleEndTime() == null ? 0L
                : tier.getSaleEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String saleStatus = tier.getSaleStatus() == null ? "INIT" : tier.getSaleStatus().toUpperCase();

        if ("ENDED".equals(saleStatus) || (endMs > 0L && nowMs >= endMs)) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "本场演出已停止售票");
        }
        if (startMs > 0L && nowMs < startMs) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "本场演出尚未开票");
        }
        if (!"V4".equals(strategyName)) {
            if (!"ON_SALE".equals(saleStatus) && !"PREHEATED".equals(saleStatus) && !"SOLD_OUT".equals(saleStatus)) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "当前演出状态不可下单: " + saleStatus);
            }
        }
    }

    private SeckillOrderVO toOrderVOFromPersisted(OrderDO order) {
        return SeckillOrderVO.builder()
                .orderNo(order.getOrderNo())
                .showId(order.getShowId())
                .tierId(order.getTierId())
                .qty(order.getQty())
                .amountCent(order.getAmountCent())
                .status(order.getStatus())
                .strategy(order.getSeckillStrategy())
                .expireTime(order.getExpireTime() == null ? null
                        : order.getExpireTime().atZone(ZoneId.systemDefault()).toOffsetDateTime())
                .build();
    }

    private ErrorCode resolveErrorCode(String code) {
        if (code == null || code.isBlank()) {
            return ErrorCode.SYSTEM_ERROR;
        }
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.getCode().equals(code)) {
                return ec;
            }
        }
        return ErrorCode.SYSTEM_ERROR;
    }
}
