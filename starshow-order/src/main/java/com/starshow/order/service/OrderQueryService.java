package com.starshow.order.service;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.vo.CurrentShowOrderVO;
import com.starshow.order.vo.OrderDetailVO;
import com.starshow.order.vo.OrderListVO;
import com.starshow.order.vo.OrderStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 链路三（节点 4）：订单状态、跨设备找回、详情与游标分页查询服务
 * 全部按基因位单分片精准路由，严格校验 userId 归属，非本人或无法验证归属一律返回 A0304。
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderShardMapper orderShardMapper;
    private final OrderActionMapper orderActionMapper;
    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;

    /**
     * GET /api/v1/orders/{orderNo}/status?showId=...
     */
    public OrderStatusVO getOrderStatus(long userId, long orderNo, Long showId) {
        // 1. 基因位精准单分片查持久订单
        String shardTable = OrderShardTableResolver.tableForOrderNo(orderNo);
        OrderDO order = orderShardMapper.selectByOrderNo(shardTable, orderNo);
        if (order != null) {
            if (!order.getUserId().equals(userId)) {
                throw new BizException(ErrorCode.ORDER_NOT_FOUND);
            }
            return OrderStatusVO.builder()
                    .orderNo(order.getOrderNo())
                    .showId(order.getShowId())
                    .status(order.getStatus())
                    .retryable(false)
                    .expireTime(toOffset(order.getExpireTime()))
                    .build();
        }

        // 2. DB 尚未落单：必须校验基因归属与 showId 同槽在途凭证
        if (!OrderShardTableResolver.geneMatchesUserId(orderNo, userId) || showId == null || showId <= 0) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        OrderRedisScripts.AttemptRecord attempt = redisScripts.readAttempt(showId, orderNo);
        OrderRedisScripts.DeductLogRecord deductLog = redisScripts.readDeductLog(showId, orderNo);
        OrderActionDO decision = orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_CREATE_DECISION);
        boolean rollbackDone = redisScripts.hasRollbackDone(showId, orderNo);

        // 校验归属，防止水平越权探测他人订单号
        Long ownerUserId = resolveOwnerUserId(attempt, deductLog, decision);
        if (ownerUserId == null || !ownerUserId.equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 若存在未释放的预扣凭证，且既未 rollback_done 也未 REJECTED -> 严格返回 PENDING_CONFIRM(retryable=false)
        if (deductLog != null && !rollbackDone && (decision == null || !OrderActionDO.STATUS_REJECTED.equals(decision.getStatus()))) {
            return OrderStatusVO.builder()
                    .orderNo(orderNo)
                    .showId(showId)
                    .status("PENDING_CONFIRM")
                    .retryable(false)
                    .pollIntervalMs(properties.getPollInitialMs())
                    .build();
        }

        // 若存在明确终止证据（CREATE_DECISION=REJECTED 或 attempt=FAILED 或已立回补墓碑 rollback_done）
        if (decision != null && OrderActionDO.STATUS_REJECTED.equals(decision.getStatus())) {
            boolean released = redisScripts.hasStockReleaseReceipt(showId, orderNo)
                    || deductLog == null && !Long.valueOf(orderNo).equals(redisScripts.readBoughtOwnerOrderNo(showId, userId));
            return OrderStatusVO.builder()
                    .orderNo(orderNo)
                    .showId(showId)
                    .status(released ? "FAILED" : "PENDING_CONFIRM")
                    .failCode(decision.getLastErrorCode())
                    .retryable(released)
                    .pollIntervalMs(released ? null : properties.getPollInitialMs())
                    .build();
        }
        if (attempt != null && "FAILED".equals(attempt.getStatus())) {
            return OrderStatusVO.builder()
                    .orderNo(orderNo)
                    .showId(showId)
                    .status("FAILED")
                    .failCode(attempt.getFailCode())
                    .retryable(attempt.isRetryable())
                    .build();
        }
        if (rollbackDone) {
            return OrderStatusVO.builder()
                    .orderNo(orderNo)
                    .showId(showId)
                    .status("FAILED")
                    .failCode(ErrorCode.DEDUCT_REJECTED_BY_TOMBSTONE.getCode())
                    .retryable(true)
                    .build();
        }

        // 其余仍处于 SUBMITTING / PENDING_CONFIRM 在途处理中
        return OrderStatusVO.builder()
                .orderNo(orderNo)
                .showId(showId)
                .status("PENDING_CONFIRM")
                .retryable(false)
                .pollIntervalMs(properties.getPollInitialMs())
                .build();
    }

    /**
     * GET /api/v1/orders/current?showId={showId}
     * 杀 App / 换设备跨设备恢复本人当前场次在途或有效订单
     */
    public CurrentShowOrderVO getCurrentShowOrder(long userId, long showId) {
        String shardTable = OrderShardTableResolver.tableForUserId(userId);
        OrderDO activeOrder = orderShardMapper.selectActiveByUserShow(shardTable, userId, showId);
        if (activeOrder != null) {
            return CurrentShowOrderVO.builder()
                    .hasOrder(true)
                    .showId(showId)
                    .orderNo(activeOrder.getOrderNo())
                    .status(activeOrder.getStatus())
                    .amountCent(activeOrder.getAmountCent())
                    .expireTime(toOffset(activeOrder.getExpireTime()))
                    .retryable(false)
                    .build();
        }

        // DB 暂无有效单：查 Redis bought_owner:{showId} 找回本人 V4 在途单号
        Long inFlightOrderNo = redisScripts.readBoughtOwnerOrderNo(showId, userId);
        if (inFlightOrderNo != null) {
            OrderStatusVO status = getOrderStatus(userId, inFlightOrderNo, showId);
            return CurrentShowOrderVO.builder()
                    .hasOrder(true)
                    .showId(showId)
                    .orderNo(inFlightOrderNo)
                    .status(status.getStatus())
                    .expireTime(status.getExpireTime())
                    .pollIntervalMs(status.getPollIntervalMs())
                    .retryable(status.isRetryable())
                    .failCode(status.getFailCode())
                    .build();
        }

        return CurrentShowOrderVO.builder()
                .hasOrder(false)
                .showId(showId)
                .retryable(true)
                .build();
    }

    /**
     * GET /api/v1/orders/{orderNo}
     */
    public OrderDetailVO getOrderDetail(long userId, long orderNo) {
        String shardTable = OrderShardTableResolver.tableForOrderNo(orderNo);
        OrderDO order = orderShardMapper.selectByOrderNo(shardTable, orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return toDetailVO(order);
    }

    /**
     * GET /api/v1/orders?status=...&cursor=...&size=20
     * 仅命中本人所属单一分片表，稳定游标 (createTime DESC, orderNo DESC)
     */
    public OrderListVO listMyOrders(long userId, String status, String cursor, Integer size) {
        int pageSize = (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();

        LocalDateTime cursorTime = null;
        Long cursorOrderNo = null;
        if (cursor != null && !cursor.isBlank()) {
            DecodedCursor decoded = decodeCursor(cursor, normalizedStatus);
            cursorTime = decoded.createTime();
            cursorOrderNo = decoded.orderNo();
        }

        String shardTable = OrderShardTableResolver.tableForUserId(userId);
        List<OrderDO> rows = orderShardMapper.selectUserOrdersCursor(
                shardTable, userId, normalizedStatus, cursorTime, cursorOrderNo, pageSize + 1
        );

        boolean hasMore = rows != null && rows.size() > pageSize;
        List<OrderDO> pageRows = (rows == null) ? Collections.emptyList()
                : (hasMore ? rows.subList(0, pageSize) : rows);

        List<OrderDetailVO> items = new ArrayList<>(pageRows.size());
        for (OrderDO row : pageRows) {
            items.add(toDetailVO(row));
        }

        String nextCursor = null;
        if (hasMore && !pageRows.isEmpty()) {
            OrderDO last = pageRows.get(pageRows.size() - 1);
            nextCursor = encodeCursor(last.getCreateTime(), last.getOrderNo(), normalizedStatus);
        }

        return OrderListVO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    private OrderDetailVO toDetailVO(OrderDO order) {
        return OrderDetailVO.builder()
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .showId(order.getShowId())
                .tierId(order.getTierId())
                .zoneId(order.getZoneId())
                .seatIndices(parseSeats(order.getSeatIndices()))
                .qty(order.getQty())
                .amountCent(order.getAmountCent())
                .status(order.getStatus())
                .saleConfigVersion(order.getSaleConfigVersion())
                .layoutVersion(order.getLayoutVersion())
                .seckillStrategy(order.getSeckillStrategy())
                .payTradeNo(order.getPayTradeNo())
                .closeReason(order.getCloseReason())
                .expireTime(toOffset(order.getExpireTime()))
                .paidTime(toOffset(order.getPaidTime()))
                .createTime(toOffset(order.getCreateTime()))
                .build();
    }

    private Long resolveOwnerUserId(OrderRedisScripts.AttemptRecord attempt,
                                    OrderRedisScripts.DeductLogRecord deductLog,
                                    OrderActionDO decision) {
        if (attempt != null && attempt.getUserId() != null) {
            return attempt.getUserId();
        }
        if (deductLog != null && deductLog.getUserId() != null) {
            return deductLog.getUserId();
        }
        if (decision != null && decision.getUserId() != null) {
            return decision.getUserId();
        }
        return null;
    }

    private List<Integer> parseSeats(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        String[] parts = csv.split(",");
        List<Integer> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isBlank()) {
                list.add(Integer.parseInt(p.trim()));
            }
        }
        return list;
    }

    private OffsetDateTime toOffset(LocalDateTime dt) {
        return dt == null ? null : dt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private String encodeCursor(LocalDateTime createTime, long orderNo, String status) {
        long epochMs = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String raw = epochMs + ":" + orderNo + ":" + (status == null ? "" : status);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private DecodedCursor decodeCursor(String cursor, String expectedStatus) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3) {
                throw new BizException(ErrorCode.INVALID_PARAM, "游标格式非法");
            }
            String boundStatus = parts[2];
            String exp = expectedStatus == null ? "" : expectedStatus;
            if (!exp.equals(boundStatus)) {
                throw new BizException(ErrorCode.INVALID_PARAM, "分页游标与当前筛选条件不匹配");
            }
            long epochMs = Long.parseLong(parts[0]);
            long orderNo = Long.parseLong(parts[1]);
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
            return new DecodedCursor(dt, orderNo);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INVALID_PARAM, "分页游标解析失败");
        }
    }

    private record DecodedCursor(LocalDateTime createTime, long orderNo) {
    }
}
