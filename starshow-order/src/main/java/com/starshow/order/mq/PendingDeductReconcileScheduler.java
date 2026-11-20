package com.starshow.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.order.config.OrderMetrics;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderInventoryMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.service.OrderPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 悬挂预扣巡检调度器：
 * 1. 严禁使用 KEYS * 或全库 SCAN！仅枚举已知在售/待恢复场次，通过 ZRANGEBYSCORE starshow:deduct_pending:{showId} 拉取超 5 分钟记录；
 * 2. 与 OrderCreateConsumer 共用 t_order_action(CREATE_DECISION) 唯一行锁裁决；
 * 3. 单次查不到订单或仅超时绝不盲目回补！仅当存在明确终止证据（例如已标 REJECTED、或已落墓碑、或已确认终止）且行锁确认未落单时才执行幂等回补。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingDeductReconcileScheduler {

    private static final int BATCH_LIMIT = 100;

    private final OrderInventoryMapper orderInventoryMapper;
    private final OrderShardMapper orderShardMapper;
    private final OrderActionMapper orderActionMapper;
    private final OrderPersistenceService persistenceService;
    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;
    private final ObjectMapper objectMapper;
    private final OrderMetrics metrics;

    @Scheduled(fixedDelayString = "${starshow.seckill.pending-reconcile-interval-ms:300000}")
    public void scheduledReconcile() {
        if (!properties.isPendingReconcileEnabled()) {
            return;
        }
        List<Long> showIds = orderInventoryMapper.selectActiveShowIdsForReconcile();
        if (showIds == null || showIds.isEmpty()) {
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        long cutoffSec = nowSec - Math.max(1L, properties.getPendingMinAgeSeconds());
        for (Long showId : showIds) {
            reconcileShowPending(showId, cutoffSec, false);
        }
    }

    /**
     * 按指定场次通过 ZRANGEBYSCORE 拉取悬挂扣减并裁决
     *
     * @param hasTerminalEvidence 是否已持有明确终止证据（常态定时巡检为 false，不凭单次查不到订单盲目回补；
     *                            当已有明确终止证据如 Broker 终态确认/人工或补偿判定终止时传 true 触发 CREATE_DECISION 裁决）
     */
    public int reconcileShowPending(long showId, long maxScoreEpochSec, boolean hasTerminalEvidence) {
        Set<String> pendingMembers = redisScripts.fetchPendingDeductOrders(showId, maxScoreEpochSec, BATCH_LIMIT);
        if (pendingMembers.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (String member : pendingMembers) {
            long orderNo;
            try {
                orderNo = Long.parseLong(member);
            } catch (NumberFormatException ex) {
                continue;
            }
            ReconcileResult res = reconcileSinglePending(showId, orderNo, hasTerminalEvidence, "PENDING_TERMINATED");
            if (res == ReconcileResult.CLEANED_EXISTING_ORDER
                    || res == ReconcileResult.ROLLED_BACK
                    || res == ReconcileResult.ALREADY_ROLLED_BACK) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * 单笔悬挂扣减安全裁决（供定时巡检与 L3 巡检 vs 延迟创单竞速测试调用）
     */
    public ReconcileResult reconcileSinglePending(long showId, long orderNo, boolean hasTerminalEvidence, String reasonCode) {
        // 1. 先按基因位精准查单分片，已落单则只清 deduct_pending，绝不回补！
        String shardTable = OrderShardTableResolver.tableForOrderNo(orderNo);
        OrderDO existingOrder = orderShardMapper.selectByOrderNo(shardTable, orderNo);
        if (existingOrder != null) {
            redisScripts.removeDeductPending(showId, orderNo);
            return ReconcileResult.CLEANED_EXISTING_ORDER;
        }

        // 2. 若已存在回补墓碑，说明之前已回补或事务已回滚，清理悬挂索引即可
        if (redisScripts.hasRollbackDone(showId, orderNo)) {
            redisScripts.removeDeductPending(showId, orderNo);
            persistenceService.markReleaseStockDone(orderNo);
            return ReconcileResult.ALREADY_ROLLED_BACK;
        }

        // 3. 读取预扣凭证与既有裁决行
        OrderRedisScripts.DeductLogRecord deductLog = redisScripts.readDeductLog(showId, orderNo);
        OrderActionDO existingDecision = orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_CREATE_DECISION);
        boolean alreadyRejected = existingDecision != null && OrderActionDO.STATUS_REJECTED.equals(existingDecision.getStatus());

        // 4. 单次查不到订单或仅超时均不足以触发回补：无明确终止证据则保持待核对
        if (!alreadyRejected && !hasTerminalEvidence) {
            log.info("Pending deduct has no terminal evidence yet, keeping for further verification: showId={}, orderNo={}",
                    showId, orderNo);
            return ReconcileResult.KEPT_FOR_VERIFICATION;
        }

        if (deductLog == null || deductLog.getUserId() == null || deductLog.getTierId() == null || deductLog.getQty() == null) {
            log.warn("Missing deduct_log details for terminal reconcile, keeping for manual review: showId={}, orderNo={}",
                    showId, orderNo);
            return ReconcileResult.KEPT_FOR_VERIFICATION;
        }

        String payload = toJsonQuietly(Map.of(
                "tierId", deductLog.getTierId(),
                "qty", deductLog.getQty(),
                "zoneId", deductLog.getZoneId() == null ? "" : deductLog.getZoneId(),
                "seatIndices", deductLog.getSeatIndices() == null ? "" : deductLog.getSeatIndices()
        ));

        // 5. 与消费端共用 CREATE_DECISION 唯一行锁裁决
        OrderPersistenceService.ReconcileDecisionOutcome decision = persistenceService.tryRejectForPendingReconcile(
                orderNo, deductLog.getUserId(), showId, reasonCode, payload
        );

        if (decision == OrderPersistenceService.ReconcileDecisionOutcome.ORDER_ALREADY_APPLIED) {
            redisScripts.removeDeductPending(showId, orderNo);
            return ReconcileResult.CLEANED_EXISTING_ORDER;
        }

        if (decision == OrderPersistenceService.ReconcileDecisionOutcome.REJECTED_SHOULD_ROLLBACK) {
            List<Integer> seats = parseSeatIndices(deductLog.getSeatIndices());
            long rolled = redisScripts.executeRollback(
                    showId,
                    deductLog.getTierId(),
                    deductLog.getZoneId(),
                    deductLog.getUserId(),
                    deductLog.getQty(),
                    orderNo,
                    Math.max(60L, properties.getDeductProofTtlSeconds()),
                    seats
            );
            persistenceService.markReleaseStockDone(orderNo);
            OrderRedisScripts.AttemptRecord attempt = redisScripts.readAttempt(showId, orderNo);
            String clientReqId = attempt == null ? null : attempt.getClientRequestId();
            redisScripts.updateAttemptAndReqStatus(
                    showId,
                    deductLog.getUserId(),
                    clientReqId,
                    orderNo,
                    "FAILED",
                    reasonCode == null ? "A0306" : reasonCode,
                    true,
                    Math.max(60L, properties.getRequestTtlSeconds())
            );
            if (rolled == 1L) {
                metrics.recordReconcileReleased();
                log.info("Pending deduct reconciled and rolled back via CREATE_DECISION lock: showId={}, orderNo={}", showId, orderNo);
                return ReconcileResult.ROLLED_BACK;
            }
            return ReconcileResult.ALREADY_ROLLED_BACK;
        }

        return ReconcileResult.KEPT_FOR_VERIFICATION;
    }

    private List<Integer> parseSeatIndices(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = csv.split(",");
        List<Integer> result = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isBlank()) {
                result.add(Integer.parseInt(p.trim()));
            }
        }
        return result;
    }

    private String toJsonQuietly(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public enum ReconcileResult {
        CLEANED_EXISTING_ORDER,
        ROLLED_BACK,
        ALREADY_ROLLED_BACK,
        KEPT_FOR_VERIFICATION
    }
}
