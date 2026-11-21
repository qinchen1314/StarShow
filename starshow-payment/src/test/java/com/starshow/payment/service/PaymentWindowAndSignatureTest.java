package com.starshow.payment.service;

import com.starshow.common.crypto.HmacSigner;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.payment.config.PaymentProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PaymentWindowAndSignatureTest {
    @Test
    void usesBothPaymentDeadlineLimits() {
        var now = LocalDateTime.of(2026, 1, 1, 10, 0);
        var settings = new PaymentProperties();
        assertEquals(now.plusSeconds(870), PaymentSettlement.deadline(now, now.plusMinutes(20), settings));
        assertEquals(now.plusSeconds(30), PaymentSettlement.deadline(now, now.plusSeconds(60), settings));
        assertEquals(now, PaymentSettlement.deadline(now, now.plusSeconds(30), settings));
    }

    @Test
    void rejectsTamperedBytesAndStaleSignedRequestsBeforeDatabaseAccess() {
        var settings = new PaymentProperties();
        settings.setCallbackSecret(java.util.UUID.randomUUID().toString());
        var service = new PaymentApplicationService(null, null, null, null, null, settings, null);
        byte[] original = "{\"amountCent\":100}".getBytes(StandardCharsets.UTF_8);
        byte[] altered = "{\"amountCent\":101}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String signature = HmacSigner.sign(settings.getCallbackSecret(), now + "\n", original);
        BizException modified = assertThrows(BizException.class, () -> service.callback(Long.toString(now), signature, altered));
        assertEquals(ErrorCode.PAYMENT_SIGN_INVALID, modified.getErrorCode());
        String old = Long.toString(now - 301);
        String expired = HmacSigner.sign(settings.getCallbackSecret(), old + "\n", original);
        BizException stale = assertThrows(BizException.class, () -> service.callback(old, expired, original));
        assertEquals(ErrorCode.PAYMENT_SIGN_INVALID, stale.getErrorCode());
    }
}
