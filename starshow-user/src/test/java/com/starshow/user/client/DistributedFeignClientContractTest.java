package com.starshow.user.client;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.result.Result;
import com.starshow.common.trace.TraceIdContext;
import feign.Request;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 实证 ISSUE-005：分布式模式 OpenFeign 客户端契约、内部鉴权头/TraceId 透传与超时配置
 */
class DistributedFeignClientContractTest {

    @Test
    void shouldForwardInternalSecretAndTraceIdAndConfigureTimeouts() {
        StarShowSecurityProperties props = new StarShowSecurityProperties();
        props.setInternalGatewaySecret("feign-test-internal-sec-7f8e9d6c");

        ShowServiceFeignClientConfig config = new ShowServiceFeignClientConfig();
        RequestInterceptor interceptor = config.internalAuthFeignInterceptor(props);
        Request.Options options = config.feignRequestOptions(1500L, 2500L);

        assertEquals(1500L, options.connectTimeoutMillis());
        assertEquals(2500L, options.readTimeoutMillis());

        TraceIdContext.set("feign-trace-001");
        try {
            RequestTemplate template = new RequestTemplate();
            interceptor.apply(template);

            Collection<String> secrets = template.headers().get(AuthHeaders.X_INTERNAL_GATEWAY_SECRET);
            Collection<String> traces = template.headers().get(TraceIdContext.HEADER_TRACE_ID);
            assertNotNull(secrets);
            assertTrue(secrets.contains("feign-test-internal-sec-7f8e9d6c"));
            assertNotNull(traces);
            assertTrue(traces.contains("feign-trace-001"));
        } finally {
            TraceIdContext.clear();
        }
    }

    @Test
    void shouldDelegateShowReadFacadeToOpenFeignClientInDistributedMode() {
        ShowServiceFeignClient feignClient = mock(ShowServiceFeignClient.class);
        ShowBasicDTO expected = ShowBasicDTO.builder()
                .showId(10003L)
                .title("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().plusHours(6))
                .saleEndTime(LocalDateTime.now().plusDays(10))
                .saleStatus("INIT")
                .build();
        when(feignClient.getShowBasic(10003L)).thenReturn(Result.ok(expected));

        FeignShowReadFacadeAdapter adapter = new FeignShowReadFacadeAdapter(feignClient);
        ShowBasicDTO actual = adapter.getShowBasic(10003L);
        assertEquals(expected, actual);
        verify(feignClient).getShowBasic(10003L);
    }
}
