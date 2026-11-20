package com.starshow.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分区物理座位元数据只读契约（用于自主选座合法性、所属票档与连座走道 segmentId 校验）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoneSeatSpecDTO implements Serializable {

    private Long showId;
    private String zoneId;
    private String layoutVersion;
    private List<SeatItem> seats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatItem implements Serializable {
        private Integer seatIndex;
        private Long tierId;
        private Integer rowNo;
        private Integer colNo;
        private String segmentId;
        private Integer status;
    }
}
