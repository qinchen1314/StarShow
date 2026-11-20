package com.starshow.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.constant.MqTopics;
import com.starshow.api.event.OrderCreateEvent;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.statemachine.OrderStatus;
import com.starshow.order.config.OrderMetrics;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.MqConsumeLogDO;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.mapper.MqConsumeLogMapper;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderInventoryMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 订单同事务原子落库服务（承接 V1~V3 同步落单与 V4 消费端三层幂等落单 + CREATE_DECISION 行锁裁决）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final OrderShardMapper orderShardMapper;
    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final OrderActionMapper orderActionMapper;
    private final OrderInventoryMapper orderInventoryMapper;
    private final GeneSnowflakeIdGenerator idGenerator;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final SeckillProperties properties;
    private final OrderMetrics metrics;

    /** 每票档并发限流信号量（保护 InnoDB t_ticket_tier 单行热点锁） */
    private final ConcurrentHashMap<Long, Semaphore> perTierSemaphores = new ConcurrentHashMap<>();

    // ---------- V1~V3 同步策略落单（同一 MySQL 事务：占座 + 扣物理库存 + 落单 + 写 CLOSE_CHECK） ----------

    public SyncOrderPersistResult persistSyncOrder(SyncOrderCommand cmd, boolean optimisticLock) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return tx.execute(status -> {
            String shardTable = OrderShardTableResolver.tableForUserId(cmd.userId());

            // 1. 幂等检查：若同请求已落单则直接返回原单
            OrderDO byRequest = orderShardMapper.selectByUserShowRequest(
                    shardTable, cmd.userId(), cmd.showId(), cmd.clientRequestId());
            if (byRequest != null) {
                if (byRequest.getRequestFingerprint() != null
                        && !byRequest.getRequestFingerprint().equals(cmd.requestFingerprint())) {
                    throw new BizException(ErrorCode.REQUEST_CONFLICT);
                }
                return new SyncOrderPersistResult(byRequest, null, false);
            }

            // 2. 自主选座：同一事务内条件占座（status=0 -> 1）
            if (cmd.seatIndices() != null && !cmd.seatIndices().isEmpty()) {
                int occupied = orderInventoryMapper.occupySeatsInDb(
                        cmd.showId(), cmd.tierId(), cmd.zoneId(), cmd.seatIndices());
                if (occupied != cmd.seatIndices().size()) {
                    status.setRollbackOnly();
                    List<Integer> conflicts = orderInventoryMapper.selectOccupiedSeatIndices(
                            cmd.showId(), cmd.zoneId(), cmd.seatIndices());
                    metrics.recordSeatConflict();
                    throw BizException.withData(
                            ErrorCode.SEAT_CONFLICT,
                            ErrorCode.SEAT_CONFLICT.getMessage(),
                            buildSeatConflictData(cmd.showId(), cmd.zoneId(), cmd.layoutVersion(), conflicts)
                    );
                }
            }

            // 3. 扣减 DB 物理库存
            if (optimisticLock) {
                OrderInventoryMapper.TierStockRow row = orderInventoryMapper.selectTierStock(cmd.showId(), cmd.tierId());
                if (row == null) {
                    status.setRollbackOnly();
                    throw new BizException(ErrorCode.TIER_NOT_FOUND);
                }
                if (row.getAvailableStock() == null || row.getAvailableStock() < cmd.qty()) {
                    status.setRollbackOnly();
                    metrics.recordStockShortage();
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
                }
                int updated = orderInventoryMapper.deductStockOptimistic(
                        cmd.showId(), cmd.tierId(), cmd.qty(), row.getVersion());
                if (updated != 1) {
                    status.setRollbackOnly();
                    return new SyncOrderPersistResult(null, null, true);
                }
            } else {
                int updated = orderInventoryMapper.deductStockAtomic(cmd.showId(), cmd.tierId(), cmd.qty());
                if (updated != 1) {
                    status.setRollbackOnly();
                    metrics.recordStockShortage();
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
                }
            }

            // 4. 写入基因分表订单（UK(user_id, show_id, active_flag) 物理兜底防同场重复）
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expireTime = now.plusMinutes(Math.max(1L, properties.getOrderExpireMinutes()));
            OrderDO order = OrderDO.builder()
                    .orderNo(cmd.orderNo())
                    .userId(cmd.userId())
                    .showId(cmd.showId())
                    .tierId(cmd.tierId())
                    .zoneId(cmd.zoneId())
                    .seatIndices(formatSeatIndices(cmd.seatIndices()))
                    .qty(cmd.qty())
                    .amountCent(cmd.amountCent())
                    .status(OrderStatus.WAIT_PAY.name())
                    .activeFlag(1L)
                    .requestId(cmd.clientRequestId())
                    .requestFingerprint(cmd.requestFingerprint())
                    .saleConfigVersion(cmd.saleConfigVersion())
                    .layoutVersion(cmd.layoutVersion())
                    .seckillStrategy(cmd.strategy())
                    .expireTime(expireTime)
                    .deleted(0)
                    .createTime(now)
                    .updateTime(now)
                    .build();

            try {
                orderShardMapper.insertOrder(shardTable, order);
            } catch (DuplicateKeyException ex) {
                status.setRollbackOnly();
                OrderDO existingReq = orderShardMapper.selectByUserShowRequest(
                        shardTable, cmd.userId(), cmd.showId(), cmd.clientRequestId());
                if (existingReq != null) {
                    return new SyncOrderPersistResult(existingReq, null, false);
                }
                metrics.recordDuplicateRejected();
                throw new BizException(ErrorCode.DUPLICATE_PURCHASE);
            }

            // 5. 同事务写入 CLOSE_CHECK 后续动作，确保 15 分钟关单任务永不丢失
            OrderActionDO closeAction = insertCloseCheckAction(order, now);
            metrics.recordPersisted(cmd.strategy());
            return new SyncOrderPersistResult(order, closeAction, false);
        });
    }

    // ---------- V4 消费端三层幂等落单（带每票档并发限流 + CREATE_DECISION 唯一行锁裁决） ----------

    public V4ConsumeOutcome persistV4OrderFromEvent(OrderCreateEvent event) {
        if (event == null || event.getOrderNo() == null || event.getUserId() == null
                || event.getShowId() == null || event.getTierId() == null
                || event.getQty() == null || event.getQty() <= 0) {
            return V4ConsumeOutcome.invalid("INVALID_EVENT");
        }
        if (!OrderShardTableResolver.geneMatchesUserId(event.getOrderNo(), event.getUserId())) {
            log.error("Gene mismatch in OrderCreateEvent: orderNo={}, userId={}", event.getOrderNo(), event.getUserId());
            return V4ConsumeOutcome.invalid("GENE_MISMATCH");
        }

        Semaphore tierSemaphore = perTierSemaphores.computeIfAbsent(
                event.getTierId(),
                id -> new Semaphore(Math.max(1, properties.getPerTierConsumerConcurrency()), true)
        );
        try {
            tierSemaphore.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting per-tier consumer semaphore", ex);
        }

        try {
            return doPersistV4OrderTransactional(event);
        } finally {
            tierSemaphore.release();
        }
    }

    private V4ConsumeOutcome doPersistV4OrderTransactional(OrderCreateEvent event) {
        long orderNo = event.getOrderNo();
        long userId = event.getUserId();
        long showId = event.getShowId();
        String shardTable = OrderShardTableResolver.tableForOrderNo(orderNo);
        String msgKey = (event.getMsgKey() != null && !event.getMsgKey().isBlank())
                ? event.getMsgKey()
                : "order-create:" + orderNo;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        V4ConsumeOutcome outcome = tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();

            // 1. 建立并 FOR UPDATE 锁定唯一 CREATE_DECISION 裁决行（消费端 vs 悬挂巡检互斥点）
            OrderActionDO decision = lockOrCreateDecisionRow(orderNo, userId, showId, now);
            if (OrderActionDO.STATUS_APPLIED.equals(decision.getStatus())) {
                OrderDO existing = orderShardMapper.selectByOrderNo(shardTable, orderNo);
                return V4ConsumeOutcome.alreadyApplied(existing);
            }
            if (OrderActionDO.STATUS_REJECTED.equals(decision.getStatus())) {
                return V4ConsumeOutcome.alreadyRejected(decision.getLastErrorCode());
            }

            // 2. 第一层幂等：检查是否已落单或写入 t_mq_consume_log
            OrderDO existingOrder = orderShardMapper.selectByOrderNo(shardTable, orderNo);
            if (existingOrder != null) {
                orderActionMapper.casUpdateStatus(orderNo, OrderActionDO.TYPE_CREATE_DECISION,
                        OrderActionDO.STATUS_PENDING, OrderActionDO.STATUS_APPLIED, null, null, now);
                return V4ConsumeOutcome.alreadyApplied(existingOrder);
            }

            try {
                mqConsumeLogMapper.insert(MqConsumeLogDO.builder()
                        .msgKey(msgKey)
                        .topic(MqTopics.ORDER_CREATE)
                        .userId(userId)
                        .bizNo(String.valueOf(orderNo))
                        .consumeStatus(1)
                        .createTime(now)
                        .build());
            } catch (DuplicateKeyException ex) {
                OrderDO recheck = orderShardMapper.selectByOrderNo(shardTable, orderNo);
                if (recheck != null) {
                    orderActionMapper.casUpdateStatus(orderNo, OrderActionDO.TYPE_CREATE_DECISION,
                            OrderActionDO.STATUS_PENDING, OrderActionDO.STATUS_APPLIED, null, null, now);
                    return V4ConsumeOutcome.alreadyApplied(recheck);
                }
                status.setRollbackOnly();
                return V4ConsumeOutcome.alreadyRejected(ErrorCode.DUPLICATE_PURCHASE.getCode());
            }

            // 3. 第二层物理兜底：扣减 t_ticket_tier.available_stock WHERE available_stock >= qty
            int stockUpdated = orderInventoryMapper.deductStockAtomic(showId, event.getTierId(), event.getQty());
            if (stockUpdated != 1) {
                status.setRollbackOnly();
                return V4ConsumeOutcome.rejectedNeedRollback(ErrorCode.STOCK_NOT_ENOUGH.getCode());
            }

            // 4. 第三层唯一索引兜底：写入 t_order_{shard}
            LocalDateTime expireTime = now.plusMinutes(Math.max(1L, properties.getOrderExpireMinutes()));
            OrderDO order = OrderDO.builder()
                    .orderNo(orderNo)
                    .userId(userId)
                    .showId(showId)
                    .tierId(event.getTierId())
                    .zoneId(event.getZoneId())
                    .seatIndices(formatSeatIndices(event.getSeatIndices()))
                    .qty(event.getQty())
                    .amountCent(event.getAmountCent())
                    .status(OrderStatus.WAIT_PAY.name())
                    .activeFlag(1L)
                    .requestId(event.getClientRequestId())
                    .requestFingerprint(event.getRequestFingerprint())
                    .saleConfigVersion(event.getSaleConfigVersion() == null ? 1 : event.getSaleConfigVersion())
                    .layoutVersion(event.getLayoutVersion())
                    .seckillStrategy(event.getStrategy() == null ? "V4" : event.getStrategy())
                    .expireTime(expireTime)
                    .deleted(0)
                    .createTime(now)
                    .updateTime(now)
                    .build();

            try {
                orderShardMapper.insertOrder(shardTable, order);
            } catch (DuplicateKeyException ex) {
                status.setRollbackOnly();
                OrderDO sameOrder = orderShardMapper.selectByOrderNo(shardTable, orderNo);
                if (sameOrder != null) {
                    return V4ConsumeOutcome.alreadyApplied(sameOrder);
                }
                return V4ConsumeOutcome.rejectedNeedRollback(ErrorCode.DUPLICATE_PURCHASE.getCode());
            }

            // 5. 同事务推进 CREATE_DECISION -> APPLIED 并写入 CLOSE_CHECK 后续关单任务
            orderActionMapper.casUpdateStatus(orderNo, OrderActionDO.TYPE_CREATE_DECISION,
                    OrderActionDO.STATUS_PENDING, OrderActionDO.STATUS_APPLIED, null, null, now);
            OrderActionDO closeAction = insertCloseCheckAction(order, now);
            metrics.recordPersisted("V4");
            return V4ConsumeOutcome.applied(order, closeAction);
        });

        // 若因物理库存或唯一约束被拒绝，在独立事务内持久化 CREATE_DECISION=REJECTED 与 RELEASE_STOCK 补偿任务
        if (outcome != null && outcome.status() == V4ConsumeStatus.REJECTED_NEED_ROLLBACK) {
            recordRejectedDecisionAndReleaseTask(event, outcome.failCode());
        }
        return outcome;
    }

    /**
     * 供悬挂巡检调用：在独立事务中通过 CREATE_DECISION 唯一行锁抢占裁决权；
     * 仅当订单确实不存在且 CREATE_DECISION 未被消费端置为 APPLIED 时，推进为 REJECTED 并写 RELEASE_STOCK 任务。
     */
    public ReconcileDecisionOutcome tryRejectForPendingReconcile(long orderNo, long userId, long showId,
                                                                 String reasonCode, String payloadJson) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            OrderActionDO decision = lockOrCreateDecisionRow(orderNo, userId, showId, now);
            String shardTable = OrderShardTableResolver.tableForOrderNo(orderNo);
            OrderDO existingOrder = orderShardMapper.selectByOrderNo(shardTable, orderNo);

            if (existingOrder != null || OrderActionDO.STATUS_APPLIED.equals(decision.getStatus())) {
                if (!OrderActionDO.STATUS_APPLIED.equals(decision.getStatus())) {
                    orderActionMapper.casUpdateStatus(orderNo, OrderActionDO.TYPE_CREATE_DECISION,
                            decision.getStatus(), OrderActionDO.STATUS_APPLIED, null, null, now);
                }
                return ReconcileDecisionOutcome.ORDER_ALREADY_APPLIED;
            }

            if (OrderActionDO.STATUS_REJECTED.equals(decision.getStatus())) {
                ensureReleaseStockAction(orderNo, userId, showId, payloadJson, reasonCode, now);
                return ReconcileDecisionOutcome.REJECTED_SHOULD_ROLLBACK;
            }

            int updated = orderActionMapper.casUpdateStatus(orderNo, OrderActionDO.TYPE_CREATE_DECISION,
                    OrderActionDO.STATUS_PENDING, OrderActionDO.STATUS_REJECTED, payloadJson, reasonCode, now);
            if (updated == 1) {
                ensureReleaseStockAction(orderNo, userId, showId, payloadJson, reasonCode, now);
                return ReconcileDecisionOutcome.REJECTED_SHOULD_ROLLBACK;
            }
            return ReconcileDecisionOutcome.CONFLICT_RETRY;
        });
    }

    public void markReleaseStockDone(long orderNo) {
        OrderActionDO action = orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_RELEASE_STOCK);
        if (action != null && OrderActionDO.STATUS_PENDING.equals(action.getStatus())) {
            orderActionMapper.casMarkDone(action.getActionId(), LocalDateTime.now());
        }
    }

    private void recordRejectedDecisionAndReleaseTask(OrderCreateEvent event, String failCode) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            OrderActionDO decision = lockOrCreateDecisionRow(event.getOrderNo(), event.getUserId(), event.getShowId(), now);
            if (OrderActionDO.STATUS_APPLIED.equals(decision.getStatus())) {
                return;
            }
            String payload = toJsonQuietly(Map.of(
                    "tierId", event.getTierId(),
                    "qty", event.getQty(),
                    "zoneId", event.getZoneId() == null ? "" : event.getZoneId(),
                    "seatIndices", formatSeatIndices(event.getSeatIndices()) == null ? "" : formatSeatIndices(event.getSeatIndices()),
                    "failCode", failCode == null ? "" : failCode
            ));
            orderActionMapper.casUpdateStatus(event.getOrderNo(), OrderActionDO.TYPE_CREATE_DECISION,
                    OrderActionDO.STATUS_PENDING, OrderActionDO.STATUS_REJECTED, payload, failCode, now);
            ensureReleaseStockAction(event.getOrderNo(), event.getUserId(), event.getShowId(), payload, failCode, now);
        });
    }

    private OrderActionDO lockOrCreateDecisionRow(long orderNo, long userId, long showId, LocalDateTime now) {
        // 第一步：若行不存在，在独立短事务(REQUIRES_NEW)内 INSERT IGNORE 预创建并提交，避免两主事务同键 INSERT IGNORE 交叉 S/X 锁死锁
        OrderActionDO existing = orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_CREATE_DECISION);
        if (existing == null) {
            ensureDecisionRowExistsIsolated(orderNo, userId, showId, now);
        }
        // 第二步：在当前主事务内对已存在的唯一索引行等值执行 FOR UPDATE（严格单行 Record Lock，零 Gap Lock，100% 互斥串行）
        OrderActionDO locked = orderActionMapper.selectByOrderAndTypeForUpdate(orderNo, OrderActionDO.TYPE_CREATE_DECISION);
        if (locked == null) {
            throw new IllegalStateException("Failed to lock CREATE_DECISION row for orderNo=" + orderNo);
        }
        return locked;
    }

    private void ensureDecisionRowExistsIsolated(long orderNo, long userId, long showId, LocalDateTime now) {
        TransactionTemplate shortTx = new TransactionTemplate(transactionManager);
        shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                shortTx.executeWithoutResult(status -> {
                    OrderActionDO created = OrderActionDO.builder()
                            .actionId(idGenerator.nextId())
                            .orderNo(orderNo)
                            .userId(userId)
                            .showId(showId)
                            .actionType(OrderActionDO.TYPE_CREATE_DECISION)
                            .status(OrderActionDO.STATUS_PENDING)
                            .nextRetryAt(now)
                            .attempt(0)
                            .deleted(0)
                            .createTime(now)
                            .updateTime(now)
                            .build();
                    orderActionMapper.insertIgnore(created);
                });
                return;
            } catch (DeadlockLoserDataAccessException | DuplicateKeyException ex) {
                if (orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_CREATE_DECISION) != null) {
                    return;
                }
                if (attempt == 2) {
                    throw ex;
                }
            }
        }
    }

    private OrderActionDO insertCloseCheckAction(OrderDO order, LocalDateTime now) {
        long expireEpochMs = order.getExpireTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String payload = toJsonQuietly(Map.of("expireEpochMs", expireEpochMs));
        OrderActionDO action = OrderActionDO.builder()
                .actionId(idGenerator.nextId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .showId(order.getShowId())
                .actionType(OrderActionDO.TYPE_CLOSE_CHECK)
                .status(OrderActionDO.STATUS_PENDING)
                .payloadJson(payload)
                .nextRetryAt(now)
                .attempt(0)
                .deleted(0)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            orderActionMapper.insert(action);
            return action;
        } catch (DuplicateKeyException ex) {
            return orderActionMapper.selectByOrderAndType(order.getOrderNo(), OrderActionDO.TYPE_CLOSE_CHECK);
        }
    }

    private void ensureReleaseStockAction(long orderNo, long userId, long showId,
                                          String payloadJson, String reasonCode, LocalDateTime now) {
        OrderActionDO existing = orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_RELEASE_STOCK);
        if (existing != null) {
            return;
        }
        try {
            orderActionMapper.insert(OrderActionDO.builder()
                    .actionId(idGenerator.nextId())
                    .orderNo(orderNo)
                    .userId(userId)
                    .showId(showId)
                    .actionType(OrderActionDO.TYPE_RELEASE_STOCK)
                    .status(OrderActionDO.STATUS_PENDING)
                    .payloadJson(payloadJson)
                    .lastErrorCode(reasonCode)
                    .nextRetryAt(now)
                    .attempt(0)
                    .deleted(0)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        } catch (DuplicateKeyException ignored) {
        }
    }

    private String toJsonQuietly(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public static String formatSeatIndices(List<Integer> seatIndices) {
        if (seatIndices == null || seatIndices.isEmpty()) {
            return null;
        }
        return seatIndices.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static Map<String, Object> buildSeatConflictData(long showId, String zoneId, String layoutVersion, List<Integer> conflicts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("showId", String.valueOf(showId));
        data.put("zoneId", zoneId);
        data.put("layoutVersion", layoutVersion);
        if (conflicts != null && !conflicts.isEmpty()) {
            data.put("conflictSeatIndices", conflicts);
        }
        return data;
    }

    public record SyncOrderCommand(
            long orderNo,
            long userId,
            long showId,
            long tierId,
            String zoneId,
            List<Integer> seatIndices,
            int qty,
            long amountCent,
            String clientRequestId,
            String requestFingerprint,
            int saleConfigVersion,
            String layoutVersion,
            String strategy
    ) {
    }

    public record SyncOrderPersistResult(OrderDO order, OrderActionDO closeAction, boolean optimisticConflict) {
    }

    public enum V4ConsumeStatus {
        APPLIED,
        ALREADY_APPLIED,
        ALREADY_REJECTED,
        REJECTED_NEED_ROLLBACK,
        INVALID
    }

    public record V4ConsumeOutcome(V4ConsumeStatus status, OrderDO order, OrderActionDO closeAction, String failCode) {
        static V4ConsumeOutcome applied(OrderDO order, OrderActionDO closeAction) {
            return new V4ConsumeOutcome(V4ConsumeStatus.APPLIED, order, closeAction, null);
        }

        static V4ConsumeOutcome alreadyApplied(OrderDO order) {
            return new V4ConsumeOutcome(V4ConsumeStatus.ALREADY_APPLIED, order, null, null);
        }

        static V4ConsumeOutcome alreadyRejected(String failCode) {
            return new V4ConsumeOutcome(V4ConsumeStatus.ALREADY_REJECTED, null, null, failCode);
        }

        static V4ConsumeOutcome rejectedNeedRollback(String failCode) {
            return new V4ConsumeOutcome(V4ConsumeStatus.REJECTED_NEED_ROLLBACK, null, null, failCode);
        }

        static V4ConsumeOutcome invalid(String failCode) {
            return new V4ConsumeOutcome(V4ConsumeStatus.INVALID, null, null, failCode);
        }
    }

    public enum ReconcileDecisionOutcome {
        ORDER_ALREADY_APPLIED,
        REJECTED_SHOULD_ROLLBACK,
        CONFLICT_RETRY
    }
}
