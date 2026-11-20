package com.starshow.common.constant;

/**
 * Redis Key 统一命名契约
 * 关键规则：同一场次参与同一段 Lua 脚本的 Key 必须共享 {showId} Hash Tag，
 * 其它动态参数严禁使用花括号，确保 Redis Cluster 零 CROSSSLOT 错误。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ---------- 鉴权与预约（链路一） ----------

    public static String authBlacklist(String jti) {
        return "starshow:auth:blacklist:" + jti;
    }

    public static String userVerified(long userId) {
        return "starshow:user:verified:" + userId;
    }

    public static String reservationBloom(long showId) {
        return "starshow:rsv:bloom:" + showId;
    }

    public static String reservationCount(long showId) {
        return "starshow:rsv:count:" + showId;
    }

    public static String reservationCountLock(long showId) {
        return "starshow:lock:rsv_count:" + showId;
    }

    // ---------- 演出缓存与座位图（链路二） ----------

    public static String showDetail(long showId) {
        return "starshow:show:detail:" + showId;
    }

    public static String showDetailRebuildLock(long showId) {
        return "starshow:lock:show_rebuild:" + showId;
    }

    public static String seatBitmap(long showId, String zoneId) {
        return "starshow:seat:bitmap:{" + showId + "}:" + zoneId;
    }

    // ---------- 抢票核心原子集群（链路三，全部共享 {showId} Hash Tag） ----------

    public static String stock(long showId, long tierId) {
        return "starshow:stock:{" + showId + "}:" + tierId;
    }

    public static String boughtUsers(long showId) {
        return "starshow:bought:{" + showId + "}";
    }

    public static String deductLog(long showId, long orderNo) {
        return "starshow:deduct_log:{" + showId + "}:" + orderNo;
    }

    public static String deductPendingZset(long showId) {
        return "starshow:deduct_pending:{" + showId + "}";
    }

    public static String rollbackDone(long showId, long orderNo) {
        return "starshow:rollback_done:{" + showId + "}:" + orderNo;
    }

    // ---------- 分布式锁（V2/V3 压测与支付关单竞速） ----------

    public static String showLock(long showId) {
        return "starshow:lock:show:{" + showId + "}";
    }

    public static String tierLock(long showId, long tierId) {
        return "starshow:lock:tier:{" + showId + "}:" + tierId;
    }

    public static String orderLock(long orderNo) {
        return "starshow:lock:order:" + orderNo;
    }

    // ---------- 入口防重 Token ----------

    public static String seckillToken(long userId, long showId, String token) {
        return "starshow:token:{" + showId + "}:" + userId + ":" + token;
    }
}
