package com.starshow.show.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 演出实时余量快照响应（GET /api/v1/shows/{showId}/stock）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowStockVO {

    private Long showId;
    private Integer saleConfigVersion;
    private OffsetDateTime asOf;
    private boolean stale;
    private String entryState;
    private List<TierStockItem> tiers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierStockItem {
        private Long tierId;
        private Integer available;
        private String stockState;
        private String hint;
    }
}
