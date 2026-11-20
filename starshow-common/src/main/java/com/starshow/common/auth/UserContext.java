package com.starshow.common.auth;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;

/**
 * 当前请求登录态 ThreadLocal 上下文（请求结束后必须 clear 防线程复用污染）
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser loginUser) {
        if (loginUser == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(loginUser);
        }
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static LoginUser requireLoginUser() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public static long requireUserId() {
        return requireLoginUser().userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
