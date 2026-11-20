package com.starshow.common.result;

import com.starshow.common.trace.TraceIdContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全局统一响应封装
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private String code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp;

    public static <T> Result<T> ok(T data) {
        return new Result<>(
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getMessage(),
                data,
                TraceIdContext.get(),
                System.currentTimeMillis()
        );
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> accepted(T data, String message) {
        return new Result<>(
                ErrorCode.ORDER_QUEUING.getCode(),
                message,
                data,
                TraceIdContext.get(),
                System.currentTimeMillis()
        );
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detailMessage) {
        return fail(errorCode.getCode(), detailMessage);
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(
                code,
                message,
                null,
                TraceIdContext.get(),
                System.currentTimeMillis()
        );
    }

    public boolean isSuccess() {
        return ErrorCode.SUCCESS.getCode().equals(this.code)
                || ErrorCode.ORDER_QUEUING.getCode().equals(this.code);
    }
}
