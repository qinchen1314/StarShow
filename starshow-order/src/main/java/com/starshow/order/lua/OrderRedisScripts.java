package com.starshow.order.lua;

import com.starshow.common.constant.RedisKeys;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链路三 Redis Cluster {showId} 单槽 Lua 脚本与热态访问封装
 * 所有脚本 KEYS 全部共享 {showId} Hash Tag，严禁出现 CROSSSLOT，严禁 KEYS/SCAN。
 */
@Slf4j
@Component
public class OrderRedisScripts {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> reqBindScript;
    private final DefaultRedisScript<Long> reqReleaseScript;
    private final DefaultRedisScript<Long> deductScript;
    private final DefaultRedisScript<List> seatDeductScript;
    private final DefaultRedisScript<Long> txCheckScript;
    private final DefaultRedisScript<Long> rollbackScript;

    public OrderRedisScripts(StringRedisTemplate stringRedisTemplate) {
        this.redis = stringRedisTemplate;
        this.reqBindScript = loadListScript("lua/seckill_req_bind.lua");
        this.reqReleaseScript = loadLongScript("lua/seckill_req_release.lua");
        this.deductScript = loadLongScript("lua/seckill_deduct.lua");
        this.seatDeductScript = loadListScript("lua/seckill_seat_deduct.lua");
        this.txCheckScript = loadLongScript("lua/seckill_tx_check.lua");
        this.rollbackScript = loadLongScript("lua/seckill_rollback.lua");
    }

    // ---------- Token 签发与消费 ----------

    public void saveSeckillToken(long userId, long showId, String token, int saleConfigVersion, long ttlSeconds) {
        String key = RedisKeys.seckillToken(userId, showId, token);
        redis.opsForValue().set(key, String.valueOf(saleConfigVersion), Duration.ofSeconds(Math.max(1L, ttlSeconds)));
    }

    public void consumeSeckillToken(long userId, long showId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            redis.delete(RedisKeys.seckillToken(userId, showId, token));
        } catch (Exception ex) {
            log.warn("Failed to delete seckillToken for userId={}, showId={}: {}", userId, showId, ex.getMessage());
        }
    }

    // ---------- 第一段短 Lua：请求绑定与快速失败释放 ----------

    @SuppressWarnings("unchecked")
    public ReqBindResult bindRequest(long showId, long userId, String clientRequestId, long candidateOrderNo,
                                     String fingerprint, long tierId, int qty, int expectedSaleConfigVersion,
                                     String seckillToken, long nowEpochMs, long reqTtlSec, long leaseMs, String strategy) {
        List<String> keys = List.of(
                RedisKeys.seckillReq(showId, userId, clientRequestId),
                RedisKeys.seckillAttempt(showId, candidateOrderNo),
                RedisKeys.seckillToken(userId, showId, seckillToken),
                RedisKeys.seckillSubmitPending(showId)
        );
        List<Object> raw = redis.execute(
                reqBindScript,
                keys,
                String.valueOf(candidateOrderNo),
                fingerprint,
                String.valueOf(userId),
                String.valueOf(showId),
                String.valueOf(tierId),
                String.valueOf(qty),
                String.valueOf(expectedSaleConfigVersion),
                String.valueOf(nowEpochMs),
                String.valueOf(reqTtlSec),
                String.valueOf(leaseMs),
                strategy,
                clientRequestId
        );
        if (raw == null || raw.size() < 5) {
            throw new IllegalStateException("seckill_req_bind.lua returned unexpected result: " + raw);
        }
        String code = asStr(raw.get(0));
        Long orderNo = parseLongOrNull(asStr(raw.get(1)));
        String status = asStr(raw.get(2));
        String failCode = asStr(raw.get(3));
        boolean leaseAcquired = "1".equals(asStr(raw.get(4)));
        return new ReqBindResult(code, orderNo, status, failCode, leaseAcquired);
    }

    public void releaseRequestBinding(long showId, long userId, String clientRequestId, long orderNo,
                                      String failCode, boolean deleteReq, boolean retryable, long nowMs, long ttlSec) {
        try {
            List<String> keys = List.of(
                    RedisKeys.seckillReq(showId, userId, clientRequestId),
                    RedisKeys.seckillAttempt(showId, orderNo),
                    RedisKeys.seckillSubmitPending(showId)
            );
            redis.execute(
                    reqReleaseScript,
                    keys,
                    String.valueOf(orderNo),
                    failCode == null ? "" : failCode,
                    deleteReq ? "1" : "0",
                    retryable ? "1" : "0",
                    String.valueOf(nowMs),
                    String.valueOf(ttlSec)
            );
        } catch (Exception ex) {
            log.warn("Failed to release seckill req binding orderNo={}: {}", orderNo, ex.getMessage());
        }
    }

    public void updateAttemptAndReqStatus(long showId, long userId, String clientRequestId, long orderNo,
                                          String status, String failCode, boolean retryable, long ttlSec) {
        try {
            String nowStr = String.valueOf(System.currentTimeMillis());
            String attemptKey = RedisKeys.seckillAttempt(showId, orderNo);
            redis.opsForHash().putAll(attemptKey, Map.of(
                    "orderNo", String.valueOf(orderNo),
                    "userId", String.valueOf(userId),
                    "showId", String.valueOf(showId),
                    "status", status,
                    "failCode", failCode == null ? "" : failCode,
                    "retryable", retryable ? "1" : "0",
                    "updatedAtMs", nowStr
            ));
            redis.expire(attemptKey, Duration.ofSeconds(Math.max(60L, ttlSec)));

            if (clientRequestId != null && !clientRequestId.isBlank()) {
                String reqKey = RedisKeys.seckillReq(showId, userId, clientRequestId);
                if (Boolean.TRUE.equals(redis.hasKey(reqKey))) {
                    redis.opsForHash().putAll(reqKey, Map.of(
                            "status", status,
                            "failCode", failCode == null ? "" : failCode,
                            "updatedAtMs", nowStr
                    ));
                    redis.expire(reqKey, Duration.ofSeconds(Math.max(60L, ttlSec)));
                }
            }
            if (!"SUBMITTING".equals(status)) {
                redis.opsForZSet().remove(RedisKeys.seckillSubmitPending(showId), String.valueOf(orderNo));
            }
        } catch (Exception ex) {
            log.warn("Failed to update attempt status orderNo={}, status={}: {}", orderNo, status, ex.getMessage());
        }
    }

    // ---------- 第二段短 Lua：V4 原子预扣、原子回查与幂等回补 ----------

    public long executeAutoDeduct(long showId, long tierId, long userId, int qty, long orderNo,
                                  long nowEpochSec, long ttlSec, int expectedVersion, long nowEpochMs) {
        List<String> keys = List.of(
                RedisKeys.stock(showId, tierId),
                RedisKeys.boughtUsers(showId),
                RedisKeys.deductLog(showId, orderNo),
                RedisKeys.deductPendingZset(showId),
                RedisKeys.rollbackDone(showId, orderNo),
                RedisKeys.showReady(showId),
                RedisKeys.boughtOwner(showId)
        );
        Long res = redis.execute(
                deductScript,
                keys,
                String.valueOf(userId),
                String.valueOf(qty),
                String.valueOf(orderNo),
                String.valueOf(tierId),
                String.valueOf(nowEpochSec),
                String.valueOf(ttlSec),
                String.valueOf(expectedVersion),
                String.valueOf(nowEpochMs)
        );
        return res == null ? -5L : res;
    }

    @SuppressWarnings("unchecked")
    public SeatDeductResult executeSeatDeduct(long showId, long tierId, String zoneId, long userId, long orderNo,
                                              List<Integer> seatIndices, long nowEpochSec, long ttlSec,
                                              int expectedVersion, long nowEpochMs) {
        List<String> keys = List.of(
                RedisKeys.stock(showId, tierId),
                RedisKeys.boughtUsers(showId),
                RedisKeys.deductLog(showId, orderNo),
                RedisKeys.deductPendingZset(showId),
                RedisKeys.rollbackDone(showId, orderNo),
                RedisKeys.showReady(showId),
                RedisKeys.boughtOwner(showId),
                RedisKeys.seatBitmap(showId, zoneId)
        );
        List<String> args = new ArrayList<>(8 + seatIndices.size());
        args.add(String.valueOf(userId));
        args.add(String.valueOf(orderNo));
        args.add(String.valueOf(tierId));
        args.add(zoneId);
        args.add(String.valueOf(nowEpochSec));
        args.add(String.valueOf(ttlSec));
        args.add(String.valueOf(expectedVersion));
        args.add(String.valueOf(nowEpochMs));
        for (Integer idx : seatIndices) {
            args.add(String.valueOf(idx));
        }

        List<Object> raw = redis.execute(seatDeductScript, keys, args.toArray());
        if (raw == null || raw.isEmpty()) {
            return new SeatDeductResult(-5L, Collections.emptyList());
        }
        long code = parseLongOrDefault(asStr(raw.get(0)), -5L);
        List<Integer> conflicts = Collections.emptyList();
        if (code == -4L && raw.size() > 1) {
            conflicts = new ArrayList<>(raw.size() - 1);
            for (int i = 1; i < raw.size(); i++) {
                Integer idx = parseIntOrNull(asStr(raw.get(i)));
                if (idx != null) {
                    conflicts.add(idx);
                }
            }
        }
        return new SeatDeductResult(code, conflicts);
    }

    /**
     * V4 事务消息原子回查：存在 deduct_log 返回 1 (COMMIT)，否则原子写 rollback_done 墓碑并返回 0 (ROLLBACK)
     */
    public long executeTxCheck(long showId, long orderNo, long ttlSec) {
        List<String> keys = List.of(
                RedisKeys.deductLog(showId, orderNo),
                RedisKeys.rollbackDone(showId, orderNo)
        );
        Long res = redis.execute(txCheckScript, keys, String.valueOf(ttlSec));
        if (res == null) {
            throw new IllegalStateException("seckill_tx_check.lua returned null");
        }
        return res;
    }

    /**
     * 回补预扣库存与座位。1 表示本次释放，0 表示凭证已存在，-1 表示证据不足。
     */
    public long executeRollback(long showId, long tierId, String zoneId, long userId, int qty, long orderNo,
                                long ttlSec, List<Integer> seatIndices) {
        String safeZone = (zoneId == null || zoneId.isBlank()) ? "_NONE_" : zoneId;
        List<String> keys = List.of(
                RedisKeys.stock(showId, tierId),
                RedisKeys.boughtUsers(showId),
                RedisKeys.rollbackDone(showId, orderNo),
                RedisKeys.deductPendingZset(showId),
                RedisKeys.seatBitmap(showId, safeZone),
                RedisKeys.boughtOwner(showId),
                RedisKeys.stockReleaseReceipt(showId, orderNo)
        );
        List<String> args = new ArrayList<>(4 + (seatIndices == null ? 0 : seatIndices.size()));
        args.add(String.valueOf(userId));
        args.add(String.valueOf(qty));
        args.add(String.valueOf(orderNo));
        args.add(String.valueOf(ttlSec));
        if (seatIndices != null) {
            for (Integer idx : seatIndices) {
                args.add(String.valueOf(idx));
            }
        }
        Long res = redis.execute(rollbackScript, keys, args.toArray());
        return res == null ? -1L : res;
    }

    public void removeDeductPending(long showId, long orderNo) {
        try {
            redis.opsForZSet().remove(RedisKeys.deductPendingZset(showId), String.valueOf(orderNo));
        } catch (Exception ex) {
            log.warn("Failed to ZREM deduct_pending showId={}, orderNo={}: {}", showId, orderNo, ex.getMessage());
        }
    }

    // ---------- 热态读取（用于查单、换设备恢复、悬挂巡检，绝不使用 KEYS/SCAN） ----------

    public ExistingReqRecord readRequestBinding(long showId, long userId, String clientRequestId) {
        Map<Object, Object> entries = redis.opsForHash().entries(RedisKeys.seckillReq(showId, userId, clientRequestId));
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return ExistingReqRecord.builder()
                .orderNo(parseLongOrNull(asStr(entries.get("orderNo"))))
                .fingerprint(asStr(entries.get("fingerprint")))
                .status(asStr(entries.get("status")))
                .failCode(asStr(entries.get("failCode")))
                .strategy(asStr(entries.get("strategy")))
                .tierId(parseLongOrNull(asStr(entries.get("tierId"))))
                .qty(parseIntOrNull(asStr(entries.get("qty"))))
                .build();
    }

    public AttemptRecord readAttempt(long showId, long orderNo) {
        Map<Object, Object> entries = redis.opsForHash().entries(RedisKeys.seckillAttempt(showId, orderNo));
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return AttemptRecord.builder()
                .orderNo(parseLongOrNull(asStr(entries.get("orderNo"))))
                .userId(parseLongOrNull(asStr(entries.get("userId"))))
                .showId(parseLongOrNull(asStr(entries.get("showId"))))
                .tierId(parseLongOrNull(asStr(entries.get("tierId"))))
                .qty(parseIntOrNull(asStr(entries.get("qty"))))
                .clientRequestId(asStr(entries.get("clientRequestId")))
                .status(asStr(entries.get("status")))
                .failCode(asStr(entries.get("failCode")))
                .retryable("1".equals(asStr(entries.get("retryable"))))
                .strategy(asStr(entries.get("strategy")))
                .build();
    }

    public DeductLogRecord readDeductLog(long showId, long orderNo) {
        Map<Object, Object> entries = redis.opsForHash().entries(RedisKeys.deductLog(showId, orderNo));
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return DeductLogRecord.builder()
                .orderNo(parseLongOrNull(asStr(entries.get("orderNo"))))
                .userId(parseLongOrNull(asStr(entries.get("userId"))))
                .tierId(parseLongOrNull(asStr(entries.get("tierId"))))
                .qty(parseIntOrNull(asStr(entries.get("qty"))))
                .zoneId(asStr(entries.get("zoneId")))
                .seatIndices(asStr(entries.get("seatIndices")))
                .tsEpochSec(parseLongOrNull(asStr(entries.get("ts"))))
                .build();
    }

    public boolean hasRollbackDone(long showId, long orderNo) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.rollbackDone(showId, orderNo)));
    }

    public boolean hasStockReleaseReceipt(long showId, long orderNo) {
        String proof = redis.opsForValue().get(RedisKeys.stockReleaseReceipt(showId, orderNo));
        return proof != null && proof.startsWith(orderNo + ":");
    }

    public boolean hasStockReleaseReceipt(long showId, long orderNo, int quantity) {
        String proof = redis.opsForValue().get(RedisKeys.stockReleaseReceipt(showId, orderNo));
        return (orderNo + ":" + quantity).equals(proof);
    }

    public Long readBoughtOwnerOrderNo(long showId, long userId) {
        Object val = redis.opsForHash().get(RedisKeys.boughtOwner(showId), String.valueOf(userId));
        return parseLongOrNull(asStr(val));
    }

    public ReadyGateSnapshot readReadyGate(long showId) {
        Map<Object, Object> entries = redis.opsForHash().entries(RedisKeys.showReady(showId));
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return ReadyGateSnapshot.builder()
                .stage(asStr(entries.get("stage")))
                .version(parseIntOrNull(asStr(entries.get("version"))))
                .saleStartEpochMs(parseLongOrNull(asStr(entries.get("saleStartEpochMs"))))
                .saleEndEpochMs(parseLongOrNull(asStr(entries.get("saleEndEpochMs"))))
                .build();
    }

    public Set<String> fetchPendingDeductOrders(long showId, long maxScoreEpochSec, int limit) {
        Set<String> members = redis.opsForZSet().rangeByScore(
                RedisKeys.deductPendingZset(showId),
                Double.NEGATIVE_INFINITY,
                (double) maxScoreEpochSec,
                0,
                limit
        );
        return members == null ? Collections.emptySet() : members;
    }

    private DefaultRedisScript<Long> loadLongScript(String classpath) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<List> loadListScript(String classpath) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        script.setResultType(List.class);
        return script;
    }

    private static String asStr(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }

    private static Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static long parseLongOrDefault(String raw, long def) {
        Long val = parseLongOrNull(raw);
        return val == null ? def : val;
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record ReqBindResult(String code, Long orderNo, String status, String failCode, boolean leaseAcquired) {
    }

    public record SeatDeductResult(long code, List<Integer> conflictSeatIndices) {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExistingReqRecord {
        private Long orderNo;
        private String fingerprint;
        private String status;
        private String failCode;
        private String strategy;
        private Long tierId;
        private Integer qty;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttemptRecord {
        private Long orderNo;
        private Long userId;
        private Long showId;
        private Long tierId;
        private Integer qty;
        private String clientRequestId;
        private String status;
        private String failCode;
        private boolean retryable;
        private String strategy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeductLogRecord {
        private Long orderNo;
        private Long userId;
        private Long tierId;
        private Integer qty;
        private String zoneId;
        private String seatIndices;
        private Long tsEpochSec;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReadyGateSnapshot {
        private String stage;
        private Integer version;
        private Long saleStartEpochMs;
        private Long saleEndEpochMs;
    }
}
