package com.starshow.payment.controller;

import com.starshow.api.facade.PaymentReadFacade;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.InternalAccess;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.payment.repository.ReconciliationRepository.Diff;
import com.starshow.payment.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class InternalPaymentController {
    private final PaymentReadFacade payments;
    private final ReconciliationService reconciliation;
    private final StarShowSecurityProperties security;

    @PostMapping("/internal/payment/close")
    public Result<PaymentReadFacade.CloseResult> close(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @RequestBody PaymentReadFacade.CloseRequest request) {
        InternalAccess.verify(security, secret);
        return Result.ok(payments.closeOrFindPaid(request));
    }

    @PostMapping("/internal/reconciliation/run")
    public Result<ReconciliationService.Run> run(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @RequestBody ReconcileRequest request) {
        InternalAccess.verify(security, secret);
        if (request.mode() != null && !"T_PLUS_1".equals(request.mode())) throw new BizException(ErrorCode.INVALID_PARAM);
        LocalDate day = request.billDate() == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1) : request.billDate();
        return Result.ok(reconciliation.run(day, Math.max(0, request.cursor()), 10));
    }

    @GetMapping("/internal/reconciliation/diffs")
    public Result<List<Diff>> diffs(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @RequestParam LocalDate billDate, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "50") int limit) {
        InternalAccess.verify(security, secret);
        return Result.ok(reconciliation.list(billDate, status, cursor, limit));
    }

    public record ReconcileRequest(String mode, LocalDate billDate, long cursor) {}
}
