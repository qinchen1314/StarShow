package com.starshow.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 短期抢票凭证响应体（POST /api/v1/seckill/token）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillTokenVO {

    private String seckillToken;
    private Long showId;
    private Integer saleConfigVersion;
    private OffsetDateTime expireAt;
    private OffsetDateTime serverTime;
}
