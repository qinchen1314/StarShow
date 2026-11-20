package com.starshow.common.auth;

/**
 * 当前登录用户上下文快照
 */
public record LoginUser(long userId, String jti, long expireEpochSec) {
}
