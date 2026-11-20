package com.starshow.show.lua;

import com.starshow.common.constant.RedisKeys;
import com.starshow.show.domain.model.ShowReadyState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 链路二 Redis Lua 原子脚本执行器与原始二进制 Bitmap 编解码器：
 * - 全部热态 Lua 脚本统一绑定 {showId} 单一 Hash Tag，零 CROSSSLOT；
 * - 分区 Bitmap 写入经十六进制在 Lua 内转原始字节流、读取直接走 RedisConnection raw byte[]，杜绝 UTF-8 破坏高位字节；
 * - 位序严格遵循 Redis 官方 MSB（最高位优先：seatIndex=0 对应 byte[0] 的 0x80）。
 */
@Slf4j
@Component
public class ShowRedisScripts {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<Long> preheatBeginScript;
    private final DefaultRedisScript<Long> preheatWriteStockScript;
    private final DefaultRedisScript<Long> preheatWriteSeatZoneScript;
    private final DefaultRedisScript<Long> preheatCommitReadyScript;
    private final DefaultRedisScript<Long> blockForRescheduleScript;
    private final DefaultRedisScript<Long> publishRescheduledScript;
    private final DefaultRedisScript<Long> markStageScript;
    private final DefaultRedisScript<Long> unlockIfOwnerScript;

    public ShowRedisScripts(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.preheatBeginScript = loadLongScript("lua/preheat_begin.lua");
        this.preheatWriteStockScript = loadLongScript("lua/preheat_write_stock.lua");
        this.preheatWriteSeatZoneScript = loadLongScript("lua/preheat_write_seat_zone.lua");
        this.preheatCommitReadyScript = loadLongScript("lua/preheat_commit_ready.lua");
        this.blockForRescheduleScript = loadLongScript("lua/show_block_for_reschedule.lua");
        this.publishRescheduledScript = loadLongScript("lua/show_publish_rescheduled.lua");
        this.markStageScript = loadLongScript("lua/show_mark_stage.lua");
        this.unlockIfOwnerScript = loadLongScript("lua/unlock_if_owner.lua");
    }

    private DefaultRedisScript<Long> loadLongScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(Long.class);
        return script;
    }

    public long preheatBegin(long showId, int version, String executionId,
                             long saleStartEpochMs, long saleEndEpochMs, long nowMs) {
        Long res = stringRedisTemplate.execute(
                preheatBeginScript,
                List.of(RedisKeys.showReady(showId)),
                new Object[]{
                        String.valueOf(version),
                        executionId,
                        String.valueOf(saleStartEpochMs),
                        String.valueOf(saleEndEpochMs),
                        String.valueOf(nowMs)
                }
        );
        return res == null ? -99L : res;
    }

    public boolean preheatWriteStockBatch(long showId, int version, String executionId, Map<Long, Integer> tierStocks) {
        if (tierStocks == null || tierStocks.isEmpty()) {
            return true;
        }
        List<String> keys = new ArrayList<>(tierStocks.size() + 1);
        keys.add(RedisKeys.showReady(showId));
        Object[] args = new Object[tierStocks.size() + 2];
        args[0] = String.valueOf(version);
        args[1] = executionId;
        int idx = 2;
        for (Map.Entry<Long, Integer> entry : tierStocks.entrySet()) {
            keys.add(RedisKeys.stock(showId, entry.getKey()));
            args[idx++] = String.valueOf(Math.max(0, entry.getValue()));
        }
        Long res = stringRedisTemplate.execute(preheatWriteStockScript, keys, args);
        return res != null && res == 1L;
    }

    public boolean preheatWriteSeatZone(long showId, String zoneId, int version, String executionId,
                                        byte[] bitmapBytes, String layoutJson) {
        String bitmapHex = toHex(bitmapBytes);
        Long res = stringRedisTemplate.execute(
                preheatWriteSeatZoneScript,
                List.of(
                        RedisKeys.showReady(showId),
                        RedisKeys.seatBitmap(showId, zoneId),
                        RedisKeys.seatLayout(showId, zoneId)
                ),
                new Object[]{
                        String.valueOf(version),
                        executionId,
                        bitmapHex,
                        layoutJson
                }
        );
        return res != null && res == 1L;
    }

    public boolean preheatCommitReady(long showId, int version, String executionId,
                                      long saleStartEpochMs, long saleEndEpochMs, long nowMs) {
        Long res = stringRedisTemplate.execute(
                preheatCommitReadyScript,
                List.of(RedisKeys.showReady(showId)),
                new Object[]{
                        String.valueOf(version),
                        executionId,
                        String.valueOf(saleStartEpochMs),
                        String.valueOf(saleEndEpochMs),
                        String.valueOf(nowMs)
                }
        );
        return res != null && res == 1L;
    }

    public long blockForReschedule(long showId, int expectedVersion, long nowMs) {
        Long res = stringRedisTemplate.execute(
                blockForRescheduleScript,
                List.of(RedisKeys.showReady(showId)),
                new Object[]{
                        String.valueOf(expectedVersion),
                        String.valueOf(nowMs)
                }
        );
        return res == null ? -99L : res;
    }

    public boolean publishRescheduled(long showId, int newVersion,
                                      long saleStartEpochMs, long saleEndEpochMs, long nowMs) {
        Long res = stringRedisTemplate.execute(
                publishRescheduledScript,
                List.of(RedisKeys.showReady(showId)),
                new Object[]{
                        String.valueOf(newVersion),
                        String.valueOf(saleStartEpochMs),
                        String.valueOf(saleEndEpochMs),
                        String.valueOf(nowMs)
                }
        );
        return res != null && res == 1L;
    }

    public boolean markStage(long showId, int expectedVersion, String targetStage, long nowMs) {
        Long res = stringRedisTemplate.execute(
                markStageScript,
                List.of(RedisKeys.showReady(showId)),
                new Object[]{
                        String.valueOf(expectedVersion),
                        targetStage,
                        String.valueOf(nowMs)
                }
        );
        return res != null && res == 1L;
    }

    public void unlockIfOwner(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(unlockIfOwnerScript, List.of(lockKey), new Object[]{token});
        } catch (Exception ex) {
            log.warn("Failed to release lock key={}: {}", lockKey, ex.getMessage());
        }
    }

    public ShowReadyState readReadyState(long showId) {
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(RedisKeys.showReady(showId));
        if (map == null || map.isEmpty()) {
            return null;
        }
        return ShowReadyState.builder()
                .version(parseInt(map.get("version")))
                .executionId(asString(map.get("executionId")))
                .stage(asString(map.get("stage")))
                .saleStartEpochMs(parseLong(map.get("saleStartEpochMs")))
                .saleEndEpochMs(parseLong(map.get("saleEndEpochMs")))
                .updatedAtMs(parseLong(map.get("updatedAtMs")))
                .build();
    }

    /**
     * 读取分区原始二进制 Bitmap 字节数组（绕过 UTF-8 String 编解码，保证每个 bit 原样无损）
     */
    public byte[] readRawSeatBitmap(long showId, String zoneId) {
        byte[] rawKey = RedisKeys.seatBitmap(showId, zoneId).getBytes(StandardCharsets.UTF_8);
        return stringRedisTemplate.execute((RedisCallback<byte[]>) connection -> connection.stringCommands().get(rawKey));
    }

    public String readSeatLayoutJson(long showId, String zoneId) {
        return stringRedisTemplate.opsForValue().get(RedisKeys.seatLayout(showId, zoneId));
    }

    /**
     * 按 Redis 官方 MSB 最高位优先（Big-Endian bit order）设置指定偏移量位为 1
     */
    public static void setBitMsb(byte[] bitmap, int seatIndex) {
        int byteIndex = seatIndex >>> 3;
        int bitMask = 0x80 >>> (seatIndex & 7);
        bitmap[byteIndex] = (byte) (bitmap[byteIndex] | bitMask);
    }

    /**
     * 按 Redis 官方 MSB 最高位优先解码指定偏移量位（true=已占用/不可选，false=空闲）
     */
    public static boolean isBitSetMsb(byte[] bitmap, int seatIndex) {
        if (bitmap == null || seatIndex < 0) {
            return true;
        }
        int byteIndex = seatIndex >>> 3;
        if (byteIndex >= bitmap.length) {
            return true;
        }
        int bitMask = 0x80 >>> (seatIndex & 7);
        return (bitmap[byteIndex] & bitMask) != 0;
    }

    public static int requiredBitmapBytes(int maxSeatIndex) {
        if (maxSeatIndex < 0) {
            return 1;
        }
        return (maxSeatIndex >>> 3) + 1;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "00";
        }
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    private String asString(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    private Integer parseInt(Object val) {
        if (val == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long parseLong(Object val) {
        if (val == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
