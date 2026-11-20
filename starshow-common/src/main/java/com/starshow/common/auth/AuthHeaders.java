package com.starshow.common.auth;

/**
 * 全链路鉴权与网关透传请求头常量
 */
public final class AuthHeaders {

    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String X_USER_ID = "X-User-Id";
    public static final String X_USER_JTI = "X-User-Jti";
    public static final String X_USER_TOKEN_EXP = "X-User-Token-Exp";
    public static final String X_INTERNAL_GATEWAY_SECRET = "X-Internal-Gateway-Secret";

    private AuthHeaders() {
    }
}
