package com.starshow.show.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Redis L2 演出详情逻辑过期快照载体（starshow:show:detail:<showId>）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowDetailCachePayload implements Serializable {

    /**
     * 负缓存标记：true 表示已确认 MySQL 中不存在该场次（TTL 30s）
     */
    private boolean notFound;

    private Long showId;
    private Integer saleConfigVersion;
    private String title;
    private String artist;
    private String city;
    private String venue;
    private Long showTimeEpochMs;
    private Long saleStartEpochMs;
    private Long saleEndEpochMs;
    private String saleStatus;
    private String seatMode;
    private String posterUrl;
    private String description;
    private List<CachedTier> tiers;

    private long refreshedAtEpochMs;
    private long expireAtEpochMs;

    public boolean isLogicallyExpired(long nowMs) {
        return nowMs >= expireAtEpochMs;
    }

    public boolean isWithinMaxStale(long nowMs, long maxStaleSeconds) {
        return (nowMs - expireAtEpochMs) <= maxStaleSeconds * 1000L;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CachedTier implements Serializable {
        private Long tierId;
        private String tierName;
        private String zoneId;
        private Long priceCent;
        private Integer totalStock;
    }
}
