package com.starshow.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 本人订单稳定游标分页列表响应体（GET /api/v1/orders）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderListVO {

    private List<OrderDetailVO> items;
    private String nextCursor;
    private boolean hasMore;
}
