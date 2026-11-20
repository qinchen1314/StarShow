package com.starshow.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.common.constant.RedisKeys;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.mapper.ReservationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路一预约实时计数一致性与回源容量保护存储（ISSUE-003 / ISSUE-008 / Phase 1.1 优化版）：
 * 1. 单 Key Hash starshow:rsv:count:<showId> { epoch, count }，全部脚本仅操作单个 Key，零 CROSSSLOT；
 * 2. 热态写路径：DB INSERT 前捕获 preInsertEpoch，写后执行单 Key 原子 Lua（仅当当前 epoch == preInsertEpoch 才 HINCRBY +1，且故意不续期 TTL）；
 * 3. 两阶段 Fencing Token 重建（ISSUE-008）：阻断锁租约超期旧重建者覆盖较新快照；
 * 4. 有界自愈收敛（ISSUE-008）：缓存带固定上限 TTL（默认 30s），跨进程硬中断最迟在 TTL 内收敛；
 * 5. 回源容量保护（Phase 1.1）：
 *    - 同场次 SingleFlight 合并并发回源，缓存过期瞬间单实例仅 1 次 MySQL COUNT(*)；
 *    - 记录 MySQL 回源指标（本地计数器 + Micrometer starshow_reservation_count_db_fallback_total）；
 *    - 维护带时间戳的进程内微快照，Redis 与 DB 双抖动时优先降级返回未过旧快照（stale=true），过旧则返 countAvailable=false 暂隐人数，绝不因展示故障触发重试预约。
 */
@Slf4j
@Component
public class ReservationCountStore {

    static final String FIELD_EPOCH = "epoch";
    static final String FIELD_COUNT = "count";
    static final String REBUILDING_PREFIX = "REBUILDING:";

    private static final long LOCK_WAIT_SECONDS = 5L;

    private static final DefaultRedisScript<Long> CAS_INCR_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('TYPE', KEYS[1]).ok == 'hash' then " +
                    "local cur = redis.call('HGET', KEYS[1], 'epoch'); " +
                    "if cur and cur == ARGV[1] then " +
                    "return redis.call('HINCRBY', KEYS[1], 'count', 1); " +
                    "end; " +
                    "end; " +
                    "return -1;",
            Long.class
    );

    private static final DefaultRedisScript<Long> FENCE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('TYPE', KEYS[1]).ok ~= 'hash' then " +
                    "redis.call('DEL', KEYS[1]); " +
                    "end; " +
                    "redis.call('HSET', KEYS[1], 'epoch', ARGV[1]); " +
                    "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])); " +
                    "return 1;",
            Long.class
    );

    /**
     * Fencing Token 原子提交脚本：仅当当前 epoch 仍为本重建者写入的 REBUILDING:<token> 时才允许提交快照并设置固定 TTL。
     * 若锁租约过期已被其他实例接管重建，返回 -1 拒绝发布旧快照！
     */
    private static final DefaultRedisScript<Long> FENCED_COMMIT_SNAPSHOT_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('TYPE', KEYS[1]).ok == 'hash' then " +
                    "if redis.call('HGET', KEYS[1], 'epoch') == ARGV[1] then " +
                    "redis.call('HSET', KEYS[1], 'count', ARGV[2], 'epoch', ARGV[3]); " +
                    "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4])); " +
                    "return tonumber(ARGV[2]); " +
                    "end; " +
                    "end; " +
                    "return -1;",
            Long.class
    );

    private final ReservationMapper reservationMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ReservationProperties properties;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    private final Set<Long> dirtyShows = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, LocalSnapshot> localSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<CountSnapshot>> inFlightRebuilds = new ConcurrentHashMap<>();
    private final AtomicLong dbFallbackCounter = new AtomicLong(0L);

    @Autowired
    public ReservationCountStore(ReservationMapper reservationMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 RedissonClient redissonClient,
                                 ReservationProperties properties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.reservationMapper = reservationMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    public ReservationCountStore(ReservationMapper reservationMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 RedissonClient redissonClient,
                                 ReservationProperties properties) {
        this(reservationMapper, stringRedisTemplate, redissonClient, properties, null);
    }

    /**
     * 在执行 MySQL INSERT 之前捕获当前计数代际 epoch（用于证明写后 CAS-INCR 未与任何快照重建交错）
     */
    public String captureEpochBeforeInsert(long showId) {
        if (dirtyShows.contains(showId)) {
            return null;
        }
        try {
            Object raw = stringRedisTemplate.opsForHash().get(RedisKeys.reservationCount(showId), FIELD_EPOCH);
            if (raw == null) {
                return null;
            }
            String epoch = String.valueOf(raw);
            if (epoch.isBlank() || epoch.startsWith(REBUILDING_PREFIX)) {
                return null;
            }
            return epoch;
        } catch (Exception ex) {
            log.warn("Failed to capture count epoch before insert for showId={}", showId, ex);
            dirtyShows.add(showId);
            return null;
        }
    }

    /**
     * MySQL INSERT 成功提交后调用：
     * - 热态且 epoch 未变：单 Key Lua 原子 HINCRBY +1（不续期 TTL，保持有界收敛窗口）；
     * - 冷启动 / 经历过重建 / 曾发生故障：进入两阶段 Fencing Token 分布式锁重建收敛。
     */
    public long incrementAfterDbInsert(long showId, String preInsertEpoch) {
        if (!dirtyShows.contains(showId) && preInsertEpoch != null && !preInsertEpoch.isBlank()) {
            try {
                Long updated = stringRedisTemplate.execute(
                        CAS_INCR_SCRIPT,
                        Collections.singletonList(RedisKeys.reservationCount(showId)),
                        preInsertEpoch
                );
                if (updated != null && updated >= 0L) {
                    recordLocalSnapshot(showId, updated);
                    return updated;
                }
            } catch (Exception ex) {
                log.warn("Redis CAS-INCR failed for showId={}, marking dirty and falling back to DB", showId, ex);
                dirtyShows.add(showId);
                return safeDbCountFallback(showId, "write_cas_incr_error");
            }
        }
        return rebuildFromDb(showId);
    }

    /**
     * 查询场次实时预约人数（兼容旧调用方，返回数值；不可用时退回 0）
     */
    public long getCount(long showId) {
        CountSnapshot snapshot = getCountSnapshot(showId);
        return snapshot.count() == null ? 0L : snapshot.count();
    }

    /**
     * 查询场次预约人数快照（带时间标记、同场次 SingleFlight 回源合并与故障陈旧降级）
     */
    public CountSnapshot getCountSnapshot(long showId) {
        long nowMs = System.currentTimeMillis();
        long localTtlMs = properties.getCountLocalSnapshotTtlMs();
        if (localTtlMs > 0L && !dirtyShows.contains(showId)) {
            LocalSnapshot cached = localSnapshots.get(showId);
            if (cached != null && (nowMs - cached.asOfEpochMs()) <= localTtlMs) {
                return new CountSnapshot(cached.count(), cached.asOfEpochMs(), false, true);
            }
        }

        if (!dirtyShows.contains(showId)) {
            try {
                List<Object> values = stringRedisTemplate.opsForHash()
                        .multiGet(RedisKeys.reservationCount(showId), List.of(FIELD_EPOCH, FIELD_COUNT));
                if (values != null && values.size() == 2 && values.get(0) != null && values.get(1) != null) {
                    String epoch = String.valueOf(values.get(0));
                    String countStr = String.valueOf(values.get(1));
                    if (!epoch.isBlank() && !epoch.startsWith(REBUILDING_PREFIX)) {
                        long count = Long.parseLong(countStr.trim());
                        long ts = recordLocalSnapshot(showId, count);
                        return new CountSnapshot(count, ts, false, true);
                    }
                }
            } catch (Exception ex) {
                log.warn("Redis read reservation count failed for showId={}, falling back via SingleFlight", showId, ex);
                dirtyShows.add(showId);
            }
        }
        return rebuildSingleFlight(showId);
    }

    private CountSnapshot rebuildSingleFlight(long showId) {
        CompletableFuture<CountSnapshot> created = new CompletableFuture<>();
        CompletableFuture<CountSnapshot> existing = inFlightRebuilds.putIfAbsent(showId, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            CountSnapshot result = executeRebuildForSnapshot(showId);
            created.complete(result);
            return result;
        } catch (Throwable ex) {
            CountSnapshot degraded = resolveStaleFallback(showId, ex);
            created.complete(degraded);
            return degraded;
        } finally {
            inFlightRebuilds.remove(showId, created);
        }
    }

    private CountSnapshot executeRebuildForSnapshot(long showId) {
        try {
            RebuildResult rebuilt = rebuildFromDbWithHook(showId, null);
            long ts = recordLocalSnapshot(showId, rebuilt.count());
            return new CountSnapshot(rebuilt.count(), ts, false, true);
        } catch (Exception ex) {
            return resolveStaleFallback(showId, ex);
        }
    }

    private CountSnapshot resolveStaleFallback(long showId, Throwable cause) {
        log.warn("Both Redis and DB failed while reading reservation count for showId={}, checking stale local snapshot",
                showId, cause);
        LocalSnapshot last = localSnapshots.get(showId);
        long nowMs = System.currentTimeMillis();
        long maxStaleMs = Math.max(0L, properties.getCountMaxStaleSeconds()) * 1000L;
        if (last != null && (nowMs - last.asOfEpochMs()) <= maxStaleMs) {
            return new CountSnapshot(last.count(), last.asOfEpochMs(), true, true);
        }
        return new CountSnapshot(null, last == null ? 0L : last.asOfEpochMs(), true, false);
    }

    public long rebuildFromDb(long showId) {
        RebuildResult rebuilt = rebuildFromDbWithHook(showId, null);
        recordLocalSnapshot(showId, rebuilt.count());
        return rebuilt.count();
    }

    /**
     * 两阶段 Fencing Token 栅栏重建（包内/测试可注入 afterSnapshotHook 确定性验证锁租约过期场景）
     */
    public RebuildResult rebuildFromDbWithHook(long showId, Runnable afterSnapshotHook) {
        String countKey = RedisKeys.reservationCount(showId);
        long ttlSeconds = Math.max(1L, properties.getCountCacheTtlSeconds());
        long leaseSeconds = Math.max(1L, properties.getCountRebuildLockLeaseSeconds());
        long fenceTtlSeconds = Math.max(ttlSeconds, leaseSeconds + 5L);

        RLock lock = null;
        boolean locked = false;
        try {
            lock = redissonClient.getLock(RedisKeys.reservationCountLock(showId));
            locked = lock.tryLock(LOCK_WAIT_SECONDS, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("Could not acquire count rebuild lock for showId={}, returning DB snapshot", showId);
                dirtyShows.add(showId);
                long fallback = countByShowIdWithMetric(showId, "lock_timeout");
                recordLocalSnapshot(showId, fallback);
                return new RebuildResult(fallback, false);
            }

            String newEpoch = UUID.randomUUID().toString().replace("-", "");
            String rebuildingEpoch = REBUILDING_PREFIX + newEpoch;

            // Phase 1 Fence：立即切换 epoch 为 REBUILDING:<newEpoch>，使旧 epoch 的在途 CAS-INCR 失效排队
            stringRedisTemplate.execute(
                    FENCE_SCRIPT,
                    Collections.singletonList(countKey),
                    rebuildingEpoch,
                    String.valueOf(fenceTtlSeconds)
            );

            // Phase 2 Snapshot：读取 MySQL 最新已提交总数（计入回源观测指标）
            long dbCount = countByShowIdWithMetric(showId, "rebuild");

            if (afterSnapshotHook != null) {
                afterSnapshotHook.run();
            }

            // Phase 3 Fencing CAS Commit：仅当仍持有本轮 rebuildingEpoch 才提交快照并写入固定 TTL
            Long committed = stringRedisTemplate.execute(
                    FENCED_COMMIT_SNAPSHOT_SCRIPT,
                    Collections.singletonList(countKey),
                    rebuildingEpoch,
                    String.valueOf(dbCount),
                    newEpoch,
                    String.valueOf(ttlSeconds)
            );
            if (committed != null && committed >= 0L) {
                dirtyShows.remove(showId);
                recordLocalSnapshot(showId, committed);
                return new RebuildResult(committed, true);
            }

            // Fencing Token 已被更新的重建者取代：严禁覆盖，直接读取最新值返回
            log.warn("Stale count rebuild fenced out for showId={}, refusing to overwrite newer snapshot", showId);
            dirtyShows.remove(showId);
            Object latestRaw = stringRedisTemplate.opsForHash().get(countKey, FIELD_COUNT);
            long latest = latestRaw == null ? dbCount : Long.parseLong(String.valueOf(latestRaw));
            recordLocalSnapshot(showId, latest);
            return new RebuildResult(latest, false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            dirtyShows.add(showId);
            long fallback = countByShowIdWithMetric(showId, "lock_interrupted");
            recordLocalSnapshot(showId, fallback);
            return new RebuildResult(fallback, false);
        } catch (Exception ex) {
            log.warn("Redis count rebuild failed for showId={}, keeping dirty marker and returning DB count", showId, ex);
            dirtyShows.add(showId);
            long fallback = countByShowIdWithMetric(showId, "redis_rebuild_error");
            recordLocalSnapshot(showId, fallback);
            return new RebuildResult(fallback, false);
        } finally {
            if (locked && lock != null && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ex) {
                    log.warn("Failed to unlock count rebuild lock for showId={}", showId, ex);
                }
            }
        }
    }

    private long safeDbCountFallback(long showId, String reason) {
        try {
            long count = countByShowIdWithMetric(showId, reason);
            recordLocalSnapshot(showId, count);
            return count;
        } catch (Exception dbEx) {
            LocalSnapshot last = localSnapshots.get(showId);
            log.warn("DB fallback count failed for showId={}, returning last local snapshot", showId, dbEx);
            return last == null ? 0L : last.count();
        }
    }

    private long countByShowIdWithMetric(long showId, String reason) {
        dbFallbackCounter.incrementAndGet();
        MeterRegistry registry = (meterRegistryProvider == null) ? null : meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("starshow.reservation.count.db.fallback",
                    "showId", String.valueOf(showId),
                    "reason", reason).increment();
        }
        return countByShowId(showId);
    }

    private long recordLocalSnapshot(long showId, long count) {
        long nowMs = System.currentTimeMillis();
        localSnapshots.put(showId, new LocalSnapshot(count, nowMs));
        return nowMs;
    }

    public void seedLocalSnapshotForTest(long showId, long count, long asOfEpochMs) {
        localSnapshots.put(showId, new LocalSnapshot(count, asOfEpochMs));
    }

    public long getDbFallbackCount() {
        return dbFallbackCounter.get();
    }

    public void resetDbFallbackCount() {
        dbFallbackCounter.set(0L);
    }

    public boolean isDirty(long showId) {
        return dirtyShows.contains(showId);
    }

    public long countByShowId(long showId) {
        Long count = reservationMapper.selectCount(
                new LambdaQueryWrapper<ReservationDO>().eq(ReservationDO::getShowId, showId)
        );
        return count == null ? 0L : count;
    }

    public record RebuildResult(long count, boolean committed) {
    }

    public record CountSnapshot(Long count, long asOfEpochMs, boolean stale, boolean countAvailable) {
    }

    private record LocalSnapshot(long count, long asOfEpochMs) {
    }
}
