package com.starshow.order.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
class OrderConfirmation {
    private final Clock clock;
    private final long closeGuardSeconds;

    @Autowired
    OrderConfirmation(@Value("${starshow.payment.close-guard-seconds:30}") long closeGuardSeconds) {
        this(Clock.systemDefaultZone(), closeGuardSeconds);
    }

    OrderConfirmation(Clock clock, long closeGuardSeconds) {
        this.clock = clock;
        this.closeGuardSeconds = closeGuardSeconds;
    }

    Guidance forStatus(String status, OffsetDateTime expireTime, Long pollIntervalMs, boolean canStartNew) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime deadline = "WAIT_PAY".equals(status) && expireTime != null
                ? expireTime.minusSeconds(closeGuardSeconds) : null;
        String nextAction = switch (status) {
            case "SUBMITTING", "PENDING_CONFIRM", "UNKNOWN" -> "CHECK_ORDER";
            case "FAILED" -> canStartNew ? "RETRY_ORDER" : "CHECK_ORDER";
            case "NOT_FOUND" -> canStartNew ? "START_ORDER" : "CHECK_ORDER";
            case "WAIT_PAY" -> deadline != null && now.isBefore(deadline) ? "PAY" : "CHECK_PAYMENT";
            case "TICKETED" -> "VIEW_TICKETS";
            case "REFUNDING", "REFUNDED" -> "VIEW_REFUND";
            default -> "VIEW_ORDER";
        };
        return new Guidance(now, deadline, nextAction, pollIntervalMs);
    }

    record Guidance(OffsetDateTime serverTime, OffsetDateTime paymentCreateDeadline,
                    String nextAction, Long retryAfterMs) {
    }
}
