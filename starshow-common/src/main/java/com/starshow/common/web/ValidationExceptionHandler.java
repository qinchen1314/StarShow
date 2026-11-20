package com.starshow.common.web;

import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Jakarta Validation 约束异常处理器（独立拆分，按 classpath 存在 ConstraintViolationException 条件装配，
 * 根治 ISSUE-006 无 validation 依赖模块启动报 NoClassDefFoundError 问题）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.INVALID_PARAM, detail));
    }
}
