package com.starshow.show.controller;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.show.dto.ShowScheduleUpdateRequest;
import com.starshow.show.service.ShowScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 分布式模式内部端点（仅接受携带合法 X-Internal-Gateway-Secret 的服务间或管理端调用）
 */
@RestController
@RequestMapping("/internal/shows")
@RequiredArgsConstructor
public class InternalShowController {

    private final ShowReadFacade showReadFacade;
    private final ShowScheduleService showScheduleService;
    private final StarShowSecurityProperties securityProperties;

    @GetMapping("/{showId}/basic")
    public Result<ShowBasicDTO> getShowBasic(
            @PathVariable("showId") long showId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret) {
        verifyInternalSecret(secret);
        return Result.ok(showReadFacade.getShowBasic(showId));
    }

    @PutMapping("/{showId}/schedule")
    public Result<ShowBasicDTO> updateSchedule(
            @PathVariable("showId") long showId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret,
            @Valid @RequestBody ShowScheduleUpdateRequest request) {
        verifyInternalSecret(secret);
        return Result.ok(showScheduleService.reschedule(showId, request.getSaleStartTime(), request.getSaleEndTime()));
    }

    private void verifyInternalSecret(String secret) {
        String expected = securityProperties.getInternalGatewaySecret();
        if (secret == null || expected == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
