package com.starshow.common.exception;

import com.starshow.common.result.ErrorCode;
import lombok.Getter;

/**
 * 统一业务异常
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Integer httpStatus;
    private final Long retryAfterMs;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null, null);
    }

    public BizException(ErrorCode errorCode, String detailMessage) {
        this(errorCode, detailMessage, null, null, null);
    }

    public BizException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        this(errorCode, detailMessage, null, null, cause);
    }

    public BizException(ErrorCode errorCode, int httpStatus, Long retryAfterMs, String detailMessage) {
        this(errorCode, detailMessage, httpStatus, retryAfterMs, null);
    }

    private BizException(ErrorCode errorCode, String detailMessage, Integer httpStatus, Long retryAfterMs, Throwable cause) {
        super(detailMessage, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
    }

    public static BizException serviceUnavailable(ErrorCode errorCode, long retryAfterMs, String detailMessage) {
        return new BizException(errorCode, 503, retryAfterMs, detailMessage);
    }
}
