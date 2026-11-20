package com.starshow.show.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 自主选座分区座位图响应（GET /api/v1/shows/{showId}/zones/{zoneId}/seats）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatMapVO {

    private Long showId;
    private String zoneId;
    private Long tierId;
    private Integer layoutVersion;
    private OffsetDateTime asOf;
    private boolean available;
    private String stockState;
    private List<SeatItem> seats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatItem {
        private int seatIndex;
        private int rowNo;
        private int colNo;
        private String segmentId;
        private boolean selectable;
    }
}
