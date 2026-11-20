package com.starshow.show.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 热态门禁 Hash（starshow:show:ready:{showId}）内存映射模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowReadyState {

    public static final String STAGE_INIT = "INIT";
    public static final String STAGE_PREPARING = "PREPARING";
    public static final String STAGE_READY = "READY";
    public static final String STAGE_OPEN = "OPEN";
    public static final String STAGE_BLOCKED = "BLOCKED";
    public static final String STAGE_ENDED = "ENDED";

    private Integer version;
    private String executionId;
    private String stage;
    private Long saleStartEpochMs;
    private Long saleEndEpochMs;
    private Long updatedAtMs;

    public boolean isReadyOrOpen() {
        return STAGE_READY.equals(stage) || STAGE_OPEN.equals(stage);
    }

    public boolean isPreparingOrBlocked() {
        return STAGE_PREPARING.equals(stage) || STAGE_BLOCKED.equals(stage) || STAGE_INIT.equals(stage);
    }

    public boolean isEnded() {
        return STAGE_ENDED.equals(stage);
    }
}
