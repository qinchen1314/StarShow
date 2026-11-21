package com.starshow.common.auth;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.crypto.HmacSigner;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;

public final class InternalAccess {
    private InternalAccess() {}

    public static void verify(StarShowSecurityProperties properties, String provided) {
        String expected = properties.getInternalGatewaySecret();
        if (expected == null || expected.isBlank() || !HmacSigner.matches(expected, provided)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
