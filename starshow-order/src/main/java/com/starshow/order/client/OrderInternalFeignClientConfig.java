package com.starshow.order.client;

import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.trace.TraceIdContext;
import feign.Request;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Order 服务内部 Feign 专属配置（不加 @Configuration，避免污染全局上下文）：
 * 自动透传 X-Internal-Gateway-Secret 与 X-Trace-Id，并设置显式连接/读取超时。
 */
public class OrderInternalFeignClientConfig {

    @Bean
    public RequestInterceptor orderInternalAuthInterceptor(StarShowSecurityProperties securityProperties) {
        return template -> {
            template.header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, securityProperties.getInternalGatewaySecret());
            template.header(TraceIdContext.HEADER_TRACE_ID, TraceIdContext.getOrCreate());
        };
    }

    @Bean
    public Request.Options orderInternalFeignRequestOptions(
            @Value("${starshow.feign.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${starshow.feign.read-timeout-ms:5000}") long readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                true
        );
    }
}
