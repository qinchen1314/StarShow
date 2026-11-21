package com.starshow.payment.client;

import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.trace.TraceIdContext;
import feign.Request;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

public class PaymentFeignConfig {
    @Bean
    RequestInterceptor paymentInternalHeaders(StarShowSecurityProperties security) {
        return template -> {
            template.header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, security.getInternalGatewaySecret());
            template.header(TraceIdContext.HEADER_TRACE_ID, TraceIdContext.getOrCreate());
        };
    }

    @Bean
    Request.Options paymentFeignOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, true);
    }
}
