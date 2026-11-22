package com.starshow.order.mq;

import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderInventoryMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.service.OrderPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingSubmissionReconcileScheduler {

    private static final int BATCH_SIZE = 100;

    private final OrderInventoryMapper inventoryMapper;
    private final OrderShardMapper orderMapper;
    private final OrderPersistenceService persistence;
    private final OrderRedisScripts redis;
    private final SeckillProperties properties;

    @Scheduled(fixedDelayString = "${starshow.seckill.pending-reconcile-interval-ms:300000}")
    public void reconcilePendingSubmissions() {
        if (!properties.isPendingReconcileEnabled()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Math.max(1000L, properties.getRequestLeaseMs());
        long afterId = 0;
        List<Long> shows;
        do {
            shows = inventoryMapper.selectShowIdsForSubmissionRecovery(afterId, BATCH_SIZE);
            for (long showId : shows) {
                try {
                    reconcileShow(showId, cutoff);
                } catch (DataAccessException ex) {
                    log.warn("Submission recovery source unavailable for showId={}: {}", showId, ex.getClass().getSimpleName());
                }
                afterId = showId;
            }
        } while (shows.size() == BATCH_SIZE);
    }

    public int reconcileShow(long showId, long cutoffMs) {
        int offset = 0;
        int resolved = 0;
        Set<String> batch;
        do {
            batch = redis.fetchPendingSubmissions(showId, cutoffMs, offset, BATCH_SIZE);
            for (String member : batch) {
                try {
                    if (reconcileSubmission(showId, Long.parseLong(member), cutoffMs)) {
                        resolved++;
                    } else {
                        offset++;
                    }
                } catch (DataAccessException | NumberFormatException ex) {
                    offset++;
                    log.warn("Submission recovery deferred for showId={}: {}", showId, ex.getClass().getSimpleName());
                }
            }
        } while (batch.size() == BATCH_SIZE);
        return resolved;
    }

    private boolean reconcileSubmission(long showId, long orderNo, long cutoffMs) {
        String shard = OrderShardTableResolver.tableForOrderNo(orderNo);
        OrderDO existing = orderMapper.selectByOrderNo(shard, orderNo);
        if (existing != null) {
            if (!Long.valueOf(showId).equals(existing.getShowId())) {
                return false;
            }
            redis.writeRequestStatus(showId, existing.getUserId(), existing.getRequestId(), orderNo,
                    existing.getStatus(), "", false, properties.getRequestTtlSeconds());
            return true;
        }

        OrderRedisScripts.AttemptRecord attempt = redis.readAttempt(showId, orderNo);
        if (attempt == null || attempt.getUserId() == null || attempt.getClientRequestId() == null
                || !Long.valueOf(showId).equals(attempt.getShowId())
                || !OrderShardTableResolver.geneMatchesUserId(orderNo, attempt.getUserId())) {
            return false;
        }
        long userId = attempt.getUserId();
        String reason = ErrorCode.DEDUCT_REJECTED_BY_TOMBSTONE.getCode();
        if (redis.hasStockReleaseReceipt(showId, orderNo)) {
            redis.writeRequestStatus(showId, userId, attempt.getClientRequestId(), orderNo,
                    "FAILED", reason, true, properties.getRequestTtlSeconds());
            return true;
        }
        long result = redis.expireSubmission(showId, userId, orderNo, cutoffMs,
                Math.max(86400L, properties.getDeductProofTtlSeconds()));
        if (result != 2L) {
            // 已预扣时继续由原扣减巡检负责库存，保留提交索引用于后续确认落单或释放。
            return false;
        }
        var decision = persistence.tryRejectForPendingReconcile(orderNo, userId, showId, reason, null);
        if (decision == OrderPersistenceService.ReconcileDecisionOutcome.REJECTED_SHOULD_ROLLBACK) {
            redis.writeRequestStatus(showId, userId, attempt.getClientRequestId(), orderNo,
                    "FAILED", reason, true, properties.getRequestTtlSeconds());
            return true;
        }
        return false;
    }
}
