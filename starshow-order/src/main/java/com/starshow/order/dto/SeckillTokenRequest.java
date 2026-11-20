package com.starshow.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 申请短期抢票凭证请求体（POST /api/v1/seckill/token）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillTokenRequest {

    @NotNull(message = "showId 不能为空")
    @Min(value = 1, message = "showId 必须为正整数")
    private Long showId;

    @Min(value = 1, message = "saleConfigVersion 必须为正整数")
    private Integer saleConfigVersion;
}
