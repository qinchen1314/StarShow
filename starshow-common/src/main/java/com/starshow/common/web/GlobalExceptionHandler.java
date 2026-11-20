package com.starshow.common.web;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局 Servlet 异常兜底处理器（不直接引用 jakarta.validation 类型，确保无 validation 依赖的模块亦可安全加载）：
 * 1. 未登录/黑名单 (A0101/A0102) -> HTTP 401；
 * 2. 参数校验错误 (A0103) -> HTTP 400；
 * 3. 其余业务拒绝 (A0104~A04xx) -> HTTP 200 + Result.fail(code, msg)；
 * 4. 未捕获系统异常 -> HTTP 500 + Result.fail(B9999)，绝不向客户端暴露堆栈或 SQL。
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBizException(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        HttpStatus status = ex.getHttpStatus() != null
                ? HttpStatus.valueOf(ex.getHttpStatus())
                : resolveHttpStatus(code);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (ex.getRetryAfterMs() != null && ex.getRetryAfterMs() > 0) {
            long retryAfterSec = Math.max(1L, (ex.getRetryAfterMs() + 999L) / 1000L);
            builder.header("Retry-After", String.valueOf(retryAfterSec));
            builder.header("X-Retry-After-Ms", String.valueOf(ex.getRetryAfterMs()));
        }
        return builder.body(Result.fail(code, ex.getMessage(), ex.getData()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.INVALID_PARAM, detail));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.INVALID_PARAM, detail));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.INVALID_PARAM));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled server exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    private HttpStatus resolveHttpStatus(ErrorCode code) {
        if (code == ErrorCode.UNAUTHORIZED || code == ErrorCode.TOKEN_REVOKED) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == ErrorCode.INVALID_PARAM) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == ErrorCode.TOO_MANY_REQUESTS) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (code == ErrorCode.STOCK_UNAVAILABLE || code == ErrorCode.MQ_SEND_FAILED) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (code == ErrorCode.SYSTEM_ERROR) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.OK;
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + (error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage());
    }
}
