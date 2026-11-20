package com.starshow.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 抢票下单请求体（POST /api/v1/seckill/orders）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderRequest {

    @NotNull(message = "showId 不能为空")
    @Min(value = 1, message = "showId 必须为正整数")
    private Long showId;

    @NotNull(message = "tierId 不能为空")
    @Min(value = 1, message = "tierId 必须为正整数")
    private Long tierId;

    @NotNull(message = "qty 不能为空")
    @Min(value = 1, message = "qty 至少为 1")
    @Max(value = 10, message = "单笔购票数量超出上限")
    private Integer qty;

    /** 自主选座区域 ID（自动配座可空） */
    private String zoneId;

    /** 自主选座 Bitmap 偏移量列表（自动配座可空） */
    private List<Integer> seatIndices;

    /** 自主选座布局版本（自主选座必传） */
    private String layoutVersion;

    @NotNull(message = "saleConfigVersion 不能为空")
    @Min(value = 1, message = "saleConfigVersion 必须为正整数")
    private Integer saleConfigVersion;

    @NotNull(message = "expectedUnitPriceCent 不能为空")
    @Min(value = 1, message = "expectedUnitPriceCent 必须为正整数")
    private Long expectedUnitPriceCent;

    @NotBlank(message = "seckillToken 不能为空")
    @Size(max = 128, message = "seckillToken 长度非法")
    private String seckillToken;

    @NotBlank(message = "clientRequestId 不能为空")
    @Size(min = 8, max = 36, message = "clientRequestId 须为 8~36 位幂等标识")
    private String clientRequestId;
}
