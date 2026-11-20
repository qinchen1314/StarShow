package com.starshow.show.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 演出列表摘要项（GET /api/v1/shows）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowSummaryVO {

    private Long showId;
    private String title;
    private String artist;
    private String city;
    private String venue;
    private OffsetDateTime showTime;
    private OffsetDateTime saleStartTime;
    private OffsetDateTime saleEndTime;
    private String seatMode;
    private String posterUrl;
}
