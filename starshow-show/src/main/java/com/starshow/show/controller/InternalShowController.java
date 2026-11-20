package com.starshow.show.controller;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.show.dto.ShowPreheatRequest;
import com.starshow.show.dto.ShowScheduleUpdateRequest;
import com.starshow.show.service.ShowPreheatService;
import com.starshow.show.service.ShowScheduleService;
import com.starshow.show.vo.ShowPreheatResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 演出域内部协调与管理写端点（仅接受携带合法 X-Internal-Gateway-Secret 的服务间或管理端调用，公网网关零路由）
 */
@RestController
@RequestMapping("/internal/shows")
@RequiredArgsConstructor
public class InternalShowController {

    private final ShowReadFacade showReadFacade;
    private final ShowScheduleService showScheduleService;
    private final ObjectProvider<ShowPreheatService> showPreheatServiceProvider;
    private final StarShowSecurityProperties securityProperties;

    @GetMapping("/{showId}/basic")
    public Result<ShowBasicDTO> getShowBasic(
            @PathVariable("showId") long showId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret) {
        verifyInternalSecret(secret);
        return Result.ok(showReadFacade.getShowBasic(showId));
    }

    @GetMapping("/{showId}/tiers/{tierId}/snapshot")
    public Result<TierSnapshotDTO> getTierSnapshot(
            @PathVariable("showId") long showId,
            @PathVariable("tierId") long tierId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret) {
        verifyInternalSecret(secret);
        TierSnapshotDTO snapshot = showReadFacade.getTierSnapshot(showId, tierId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.TIER_NOT_FOUND);
        }
        return Result.ok(snapshot);
    }

    @PutMapping("/{showId}/schedule")
    public Result<ShowBasicDTO> updateSchedule(
            @PathVariable("showId") long showId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret,
            @Valid @RequestBody ShowScheduleUpdateRequest request) {
        verifyInternalSecret(secret);
        return Result.ok(showScheduleService.reschedule(showId, request.getSaleStartTime(), request.getSaleEndTime()));
    }

    @PostMapping("/{showId}/preheat")
    public Result<ShowPreheatResultVO> preheat(
            @PathVariable("showId") long showId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret,
            @Valid @RequestBody(required = false) ShowPreheatRequest request) {
        verifyInternalSecret(secret);
        ShowPreheatService preheatService = showPreheatServiceProvider.getIfAvailable();
        if (preheatService == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "预热服务未装配");
        }
        Integer requestedVersion = request == null ? null : request.getSaleConfigVersion();
        return Result.ok(preheatService.preheat(showId, requestedVersion));
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
