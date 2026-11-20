package com.starshow.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationCountVO {

    private Long showId;
    private Long count;
    private Long asOfEpochMs;
    private boolean stale;
    private boolean countAvailable;
}
