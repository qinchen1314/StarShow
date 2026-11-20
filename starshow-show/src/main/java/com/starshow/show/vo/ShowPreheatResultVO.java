package com.starshow.show.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 版本化预热执行与状态查询响应（POST /internal/shows/{showId}/preheat）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowPreheatResultVO {

    private Long showId;
    private Integer saleConfigVersion;
    /**
     * 面向运营的状态：PREPARING / READY / FAILED
     */
    private String taskStatus;
    private String failedStage;
    private boolean retryable;
    private String executionId;
}
