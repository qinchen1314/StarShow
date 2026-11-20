package com.starshow.common.id;

/**
 * 基因法 Snowflake 订单号生成器（严格 64 位无主键冲突划分）
 *
 * 位宽结构（总计 64 bit，高位到低位）：
 * - 1  bit : 符号位（固定 0，保证正数）
 * - 41 bit : 毫秒级时间戳偏移（可用约 69 年，基准 2026-01-01T00:00:00Z）
 * - 6  bit : 机器/节点 ID（0..63）
 * - 12 bit : 毫秒内自增序列号（每毫秒 4096 单 = 单节点 409万 QPS）
 * - 4  bit : userId 基因位（userId & 0x0F，支持 2/4/8/16 分片无广播路由）
 *
 * 关键特性：
 * 1. 基因位独立占用最低 4 bit，严禁覆盖序列号位，杜绝同毫秒主键冲突；
 * 2. 按 user_id 或按 order_no 查询均可通过 extractGene(orderNo) % shardCount 精准路由至同一物理分片；
 * 3. 对外 JSON 必须序列化为 String，防止 JS Number 超过 2^53-1 导致尾部基因位精度丢失。
 */
public class GeneSnowflakeIdGenerator {

    /** 自定义纪元：2026-01-01 00:00:00 UTC */
    private static final long EPOCH = 1767225600000L;

    private static final long GENE_BITS = 4L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_BITS = 6L;

    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;       // 63
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;      // 4095
    private static final long GENE_MASK = (1L << GENE_BITS) - 1;             // 0x0F (15)

    private static final long SEQUENCE_SHIFT = GENE_BITS;                    // 4
    private static final long WORKER_SHIFT = GENE_BITS + SEQUENCE_BITS;      // 16
    private static final long TIMESTAMP_SHIFT = WORKER_SHIFT + WORKER_BITS;  // 22

    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public GeneSnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    /**
     * 生成嵌入 userId 基因位的 64 位订单号
     */
    public synchronized long nextOrderId(long userId) {
        long current = currentMillis();
        if (current < lastTimestamp) {
            long offset = lastTimestamp - current;
            if (offset <= MAX_BACKWARD_MS) {
                current = waitUntil(lastTimestamp);
            } else {
                throw new IllegalStateException("Clock moved backwards by " + offset + "ms, refusing to generate id");
            }
        }

        if (current == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                current = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = current;

        long gene = Math.floorMod(userId, 16L) & GENE_MASK;
        return ((current - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | (sequence << SEQUENCE_SHIFT)
                | gene;
    }

    /**
     * 生成普通非基因 Snowflake ID（gene 位固定为 0，用于预约/支付/退款/电子票等主键）
     */
    public long nextId() {
        return nextOrderId(0L);
    }

    /**
     * 从订单号反解 4 bit 基因值（0..15）
     */
    public static int extractGene(long orderNo) {
        return (int) (orderNo & GENE_MASK);
    }

    /**
     * 计算分片索引（shardCount 必须为 1/2/4/8/16 等 2 的幂次且 <= 16）
     */
    public static int resolveShardByOrderNo(long orderNo, int shardCount) {
        validateShardCount(shardCount);
        return extractGene(orderNo) & (shardCount - 1);
    }

    public static int resolveShardByUserId(long userId, int shardCount) {
        validateShardCount(shardCount);
        return (int) Math.floorMod(userId, shardCount);
    }

    private static void validateShardCount(int shardCount) {
        if (shardCount <= 0 || shardCount > 16 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shardCount must be power of 2 and <= 16, actual=" + shardCount);
        }
    }

    private long waitNextMillis(long lastTs) {
        long ts = currentMillis();
        while (ts <= lastTs) {
            Thread.onSpinWait();
            ts = currentMillis();
        }
        return ts;
    }

    private long waitUntil(long targetTs) {
        long ts = currentMillis();
        while (ts < targetTs) {
            Thread.onSpinWait();
            ts = currentMillis();
        }
        return ts;
    }

    protected long currentMillis() {
        return System.currentTimeMillis();
    }
}
