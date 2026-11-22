package com.starshow.order.vo;

import java.time.OffsetDateTime;

public record SeckillRequestStatusVO(String status, Long orderNo, boolean canStartNew, Long retryAfterMs,
                                    OffsetDateTime expireTime, OffsetDateTime serverTime,
                                    OffsetDateTime paymentCreateDeadline, String nextAction) {
}
