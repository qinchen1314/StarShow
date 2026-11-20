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
    private final Object data;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null, null, null);
    }

    public BizException(ErrorCode errorCode, String detailMessage) {
        this(errorCode, detailMessage, null, null, null, null);
    }

    public BizException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        this(errorCode, detailMessage, null, null, null, cause);
    }

    public BizException(ErrorCode errorCode, int httpStatus, Long retryAfterMs, String detailMessage) {
        this(errorCode, detailMessage, httpStatus, retryAfterMs, null, null);
    }

    private BizException(ErrorCode errorCode, String detailMessage, Integer httpStatus, Long retryAfterMs, Object data, Throwable cause) {
        super(detailMessage, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
        this.data = data;
    }

    public static BizException withData(ErrorCode errorCode, String detailMessage, Object data) {
        return new BizException(errorCode, detailMessage, null, null, data, null);
    }

    public static BizException serviceUnavailable(ErrorCode errorCode, long retryAfterMs, String detailMessage) {
        return new BizException(errorCode, detailMessage, 503, retryAfterMs, null, null);
    }

    public static BizException serviceUnavailableWithData(ErrorCode errorCode, long retryAfterMs, String detailMessage, Object data) {
        return new BizException(errorCode, detailMessage, 503, retryAfterMs, data, null);
    }
}
