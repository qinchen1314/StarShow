package com.starshow.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationVO {

    private Long reservationId;
    private Long showId;
    private Long currentCount;
    private boolean remindScheduled;
    private Integer saleConfigVersion;
}
