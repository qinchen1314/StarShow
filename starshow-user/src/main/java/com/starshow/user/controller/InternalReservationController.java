package com.starshow.user.controller;

import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.user.mq.ShowRemindRecoveryScheduler;
import com.starshow.user.vo.ShowRescheduleResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 链路一内部协调端点：主办方改期后联动重排该场次已有预约提醒至最新 saleConfigVersion
 */
@RestController
@RequestMapping("/internal/reservations")
@RequiredArgsConstructor
public class InternalReservationController {

    private final ShowRemindRecoveryScheduler recoveryScheduler;
    private final StarShowSecurityProperties securityProperties;

    @PostMapping("/shows/{showId}/reschedule")
    public Result<ShowRescheduleResultVO> rescheduleShowReminders(
            @PathVariable("showId") long showId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret) {
        String expected = securityProperties.getInternalGatewaySecret();
        if (secret == null || expected == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return Result.ok(recoveryScheduler.rescheduleForShowVersion(showId));
    }
}
