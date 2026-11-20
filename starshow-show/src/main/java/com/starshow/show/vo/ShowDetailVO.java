package com.starshow.show.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 演出详情响应（GET /api/v1/shows/{showId}）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowDetailVO {

    private Long showId;
    private Integer saleConfigVersion;
    private String title;
    private String artist;
    private String city;
    private String venue;
    private OffsetDateTime showTime;
    private OffsetDateTime saleStartTime;
    private OffsetDateTime saleEndTime;
    private OffsetDateTime serverTime;
    private String saleStatus;
    private String entryState;
    private String entryHint;
    private String seatMode;
    private String posterUrl;
    private String description;
    private List<TierItem> tiers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierItem {
        private Long tierId;
        private String tierName;
        private Long priceCent;
        private String zoneId;
        private Integer available;
        private String stockState;
    }
}
