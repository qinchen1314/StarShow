package com.starshow.user.client;

import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.trace.TraceIdContext;
import feign.Request;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * ShowServiceFeignClient 专属配置（故意不加 @Configuration，避免全局污染主上下文）：
 * 统一透传 X-Internal-Gateway-Secret 与 X-Trace-Id，并显式设置连接与读取超时。
 */
public class ShowServiceFeignClientConfig {

    @Bean
    public RequestInterceptor internalAuthFeignInterceptor(StarShowSecurityProperties securityProperties) {
        return template -> {
            template.header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, securityProperties.getInternalGatewaySecret());
            template.header(TraceIdContext.HEADER_TRACE_ID, TraceIdContext.getOrCreate());
        };
    }

    @Bean
    public Request.Options feignRequestOptions(
            @Value("${starshow.feign.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${starshow.feign.read-timeout-ms:3000}") long readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                true
        );
    }
}
