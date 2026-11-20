package com.starshow.order.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路三核心可观测指标（供 Prometheus / Grafana 与 Prove-It 验收核对）
 */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong persistedCount = new AtomicLong();
    private final AtomicLong seatConflictCount = new AtomicLong();
    private final AtomicLong stockShortageCount = new AtomicLong();
    private final AtomicLong duplicateRejectedCount = new AtomicLong();
    private final AtomicLong txCheckCommitCount = new AtomicLong();
    private final AtomicLong txCheckRollbackCount = new AtomicLong();
    private final AtomicLong reconcileReleasedCount = new AtomicLong();

    public OrderMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider == null ? null : registryProvider.getIfAvailable();
    }

    public void recordAccepted(String strategy) {
        acceptedCount.incrementAndGet();
        inc("starshow.seckill.accepted.total", "strategy", strategy);
    }

    public void recordPersisted(String strategy) {
        persistedCount.incrementAndGet();
        inc("starshow.order.persisted.total", "strategy", strategy);
    }

    public void recordSeatConflict() {
        seatConflictCount.incrementAndGet();
        inc("starshow.seckill.seat_conflict.total");
    }

    public void recordStockShortage() {
        stockShortageCount.incrementAndGet();
        inc("starshow.seckill.stock_shortage.total");
    }

    public void recordDuplicateRejected() {
        duplicateRejectedCount.incrementAndGet();
        inc("starshow.seckill.duplicate_rejected.total");
    }

    public void recordTxCheckCommit() {
        txCheckCommitCount.incrementAndGet();
        inc("starshow.seckill.tx_check.commit.total");
    }

    public void recordTxCheckRollback() {
        txCheckRollbackCount.incrementAndGet();
        inc("starshow.seckill.tx_check.rollback.total");
    }

    public void recordReconcileReleased() {
        reconcileReleasedCount.incrementAndGet();
        inc("starshow.seckill.reconcile.released.total");
    }

    public long getAcceptedCount() {
        return acceptedCount.get();
    }

    public long getPersistedCount() {
        return persistedCount.get();
    }

    public long getTxCheckCommitCount() {
        return txCheckCommitCount.get();
    }

    public long getTxCheckRollbackCount() {
        return txCheckRollbackCount.get();
    }

    public long getReconcileReleasedCount() {
        return reconcileReleasedCount.get();
    }

    private void inc(String name, String... tags) {
        if (registry != null) {
            Counter.builder(name).tags(tags).register(registry).increment();
        }
    }
}
