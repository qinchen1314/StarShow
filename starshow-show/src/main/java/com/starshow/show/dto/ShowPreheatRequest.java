package com.starshow.show.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 版本化预热请求（POST /internal/shows/{showId}/preheat）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowPreheatRequest {

    /**
     * 期望预热的开售配置版本号（可选；提供时必须与 DB 当前 sale_config_version 一致，否则拒绝 A0205）
     */
    @Min(value = 1, message = "saleConfigVersion 必须大于等于 1")
    private Integer saleConfigVersion;
}
