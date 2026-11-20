package com.starshow.show.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 演出域统一时间转换工具（严格显式使用 Asia/Shanghai +08:00，所有售卖窗口比较均按绝对毫秒时间戳进行）
 */
public final class ShowTimeUtil {

    public static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    public static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    private ShowTimeUtil() {
    }

    public static long toEpochMs(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return 0L;
        }
        return localDateTime.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime toLocalDateTime(Long epochMs) {
        if (epochMs == null || epochMs <= 0L) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), SHANGHAI_ZONE);
    }

    public static OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(SHANGHAI_ZONE).toOffsetDateTime();
    }

    public static OffsetDateTime toOffsetDateTime(Long epochMs) {
        if (epochMs == null || epochMs <= 0L) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), SHANGHAI_ZONE);
    }

    public static OffsetDateTime nowOffsetDateTime() {
        return OffsetDateTime.now(SHANGHAI_ZONE);
    }

    public static LocalDateTime nowLocalDateTime() {
        return LocalDateTime.now(SHANGHAI_ZONE);
    }
}
