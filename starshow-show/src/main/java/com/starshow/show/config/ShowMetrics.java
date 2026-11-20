package com.starshow.show.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路二可观测性指标采集器（同时支持 Micrometer Prometheus 暴露与测试精准读取）：
 * 覆盖 L1/L2 命中、陈旧快照命中、DB 回源重建、锁竞争、重建队列拒绝、布隆拦截、预热耗时/成败、库存缺键与售罄响应。
 */
@Component
public class ShowMetrics {

    private final AtomicLong detailL1Hits = new AtomicLong();
    private final AtomicLong detailL2Hits = new AtomicLong();
    private final AtomicLong detailL2StaleHits = new AtomicLong();
    private final AtomicLong detailDbRebuilds = new AtomicLong();
    private final AtomicLong detailRebuildLockConflicts = new AtomicLong();
    private final AtomicLong detailRebuildQueueRejects = new AtomicLong();
    private final AtomicLong detailBloomRejects = new AtomicLong();
    private final AtomicLong preheatSuccessCount = new AtomicLong();
    private final AtomicLong preheatFailureCount = new AtomicLong();
    private final AtomicLong preheatLastDurationMs = new AtomicLong();
    private final AtomicLong stockMissingKeyCount = new AtomicLong();
    private final AtomicLong stockSoldOutResponseCount = new AtomicLong();

    private final Counter l1HitCounter;
    private final Counter l2HitCounter;
    private final Counter l2StaleCounter;
    private final Counter dbRebuildCounter;
    private final Counter lockConflictCounter;
    private final Counter queueRejectCounter;
    private final Counter bloomRejectCounter;
    private final Counter preheatSuccessCounter;
    private final Counter preheatFailureCounter;
    private final Counter stockMissingCounter;
    private final Counter stockSoldOutCounter;

    public ShowMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry registry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
        if (registry != null) {
            this.l1HitCounter = registry.counter("starshow.show.detail.l1.hit");
            this.l2HitCounter = registry.counter("starshow.show.detail.l2.hit");
            this.l2StaleCounter = registry.counter("starshow.show.detail.l2.stale.hit");
            this.dbRebuildCounter = registry.counter("starshow.show.detail.db.rebuild");
            this.lockConflictCounter = registry.counter("starshow.show.detail.rebuild.lock.conflict");
            this.queueRejectCounter = registry.counter("starshow.show.detail.rebuild.queue.reject");
            this.bloomRejectCounter = registry.counter("starshow.show.detail.bloom.reject");
            this.preheatSuccessCounter = registry.counter("starshow.show.preheat.success");
            this.preheatFailureCounter = registry.counter("starshow.show.preheat.failure");
            this.stockMissingCounter = registry.counter("starshow.show.stock.missing.key");
            this.stockSoldOutCounter = registry.counter("starshow.show.stock.sold_out");
            registry.gauge("starshow.show.preheat.last_duration_ms", preheatLastDurationMs);
        } else {
            this.l1HitCounter = null;
            this.l2HitCounter = null;
            this.l2StaleCounter = null;
            this.dbRebuildCounter = null;
            this.lockConflictCounter = null;
            this.queueRejectCounter = null;
            this.bloomRejectCounter = null;
            this.preheatSuccessCounter = null;
            this.preheatFailureCounter = null;
            this.stockMissingCounter = null;
            this.stockSoldOutCounter = null;
        }
    }

    public ShowMetrics() {
        this((ObjectProvider<MeterRegistry>) null);
    }

    public void recordDetailL1Hit() {
        detailL1Hits.incrementAndGet();
        inc(l1HitCounter);
    }

    public void recordDetailL2Hit() {
        detailL2Hits.incrementAndGet();
        inc(l2HitCounter);
    }

    public void recordDetailL2StaleHit() {
        detailL2StaleHits.incrementAndGet();
        inc(l2StaleCounter);
    }

    public void recordDetailDbRebuild() {
        detailDbRebuilds.incrementAndGet();
        inc(dbRebuildCounter);
    }

    public void recordDetailRebuildLockConflict() {
        detailRebuildLockConflicts.incrementAndGet();
        inc(lockConflictCounter);
    }

    public void recordDetailRebuildQueueReject() {
        detailRebuildQueueRejects.incrementAndGet();
        inc(queueRejectCounter);
    }

    public void recordDetailBloomReject() {
        detailBloomRejects.incrementAndGet();
        inc(bloomRejectCounter);
    }

    public void recordPreheatSuccess(long durationMs) {
        preheatSuccessCount.incrementAndGet();
        preheatLastDurationMs.set(Math.max(0L, durationMs));
        inc(preheatSuccessCounter);
    }

    public void recordPreheatFailure(long durationMs) {
        preheatFailureCount.incrementAndGet();
        preheatLastDurationMs.set(Math.max(0L, durationMs));
        inc(preheatFailureCounter);
    }

    public void recordStockMissingKey() {
        stockMissingKeyCount.incrementAndGet();
        inc(stockMissingCounter);
    }

    public void recordStockSoldOutResponse() {
        stockSoldOutResponseCount.incrementAndGet();
        inc(stockSoldOutCounter);
    }

    public long getDetailL1Hits() {
        return detailL1Hits.get();
    }

    public long getDetailL2Hits() {
        return detailL2Hits.get();
    }

    public long getDetailL2StaleHits() {
        return detailL2StaleHits.get();
    }

    public long getDetailDbRebuilds() {
        return detailDbRebuilds.get();
    }

    public long getDetailRebuildLockConflicts() {
        return detailRebuildLockConflicts.get();
    }

    public long getDetailRebuildQueueRejects() {
        return detailRebuildQueueRejects.get();
    }

    public long getDetailBloomRejects() {
        return detailBloomRejects.get();
    }

    public long getPreheatSuccessCount() {
        return preheatSuccessCount.get();
    }

    public long getPreheatFailureCount() {
        return preheatFailureCount.get();
    }

    public long getPreheatLastDurationMs() {
        return preheatLastDurationMs.get();
    }

    public long getStockMissingKeyCount() {
        return stockMissingKeyCount.get();
    }

    public long getStockSoldOutResponseCount() {
        return stockSoldOutResponseCount.get();
    }

    public void resetCountersForTest() {
        detailL1Hits.set(0);
        detailL2Hits.set(0);
        detailL2StaleHits.set(0);
        detailDbRebuilds.set(0);
        detailRebuildLockConflicts.set(0);
        detailRebuildQueueRejects.set(0);
        detailBloomRejects.set(0);
        preheatSuccessCount.set(0);
        preheatFailureCount.set(0);
        preheatLastDurationMs.set(0);
        stockMissingKeyCount.set(0);
        stockSoldOutResponseCount.set(0);
    }

    private void inc(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
