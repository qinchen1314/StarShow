package com.starshow.order.vo;

public record SeckillRequestStatusVO(String status, Long orderNo, boolean canStartNew, Long retryAfterMs) {
}
