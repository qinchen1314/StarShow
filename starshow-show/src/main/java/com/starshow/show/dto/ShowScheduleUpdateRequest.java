package com.starshow.show.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 主办方内部改期请求体（用于更新未开售场次的开售/停售时间并推进 sale_config_version）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowScheduleUpdateRequest {

    @NotNull(message = "saleStartTime 不能为空")
    private LocalDateTime saleStartTime;

    @NotNull(message = "saleEndTime 不能为空")
    private LocalDateTime saleEndTime;
}
