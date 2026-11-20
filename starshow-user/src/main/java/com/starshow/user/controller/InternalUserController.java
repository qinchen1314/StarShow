package com.starshow.user.controller;

import com.starshow.api.facade.UserFacade;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 用户域内部只读端点（仅接受携带合法 X-Internal-Gateway-Secret 的服务间调用，公网网关零路由）
 * 供 distributed 模式下 starshow-order 通过 OpenFeign 调用 UserFacade.isRealNameVerified。
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserFacade userFacade;
    private final StarShowSecurityProperties securityProperties;

    @GetMapping("/{userId}/verified")
    public Result<Boolean> isRealNameVerified(
            @PathVariable("userId") long userId,
            @RequestHeader(value = AuthHeaders.X_INTERNAL_GATEWAY_SECRET, required = false) String secret) {
        verifyInternalSecret(secret);
        return Result.ok(userFacade.isRealNameVerified(userId));
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
