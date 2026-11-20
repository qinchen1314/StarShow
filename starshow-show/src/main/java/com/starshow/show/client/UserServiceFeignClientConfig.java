package com.starshow.show.client;

import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.trace.TraceIdContext;
import feign.Request;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * UserServiceFeignClient 专属配置（不加 @Configuration，避免污染全局上下文）
 */
public class UserServiceFeignClientConfig {

    @Bean
    public RequestInterceptor userServiceInternalAuthInterceptor(StarShowSecurityProperties securityProperties) {
        return template -> {
            template.header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, securityProperties.getInternalGatewaySecret());
            template.header(TraceIdContext.HEADER_TRACE_ID, TraceIdContext.getOrCreate());
        };
    }

    @Bean
    public Request.Options userServiceFeignRequestOptions(
            @Value("${starshow.feign.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${starshow.feign.read-timeout-ms:5000}") long readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                true
        );
    }
}
