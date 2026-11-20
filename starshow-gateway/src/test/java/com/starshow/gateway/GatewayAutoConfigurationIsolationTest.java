package com.starshow.gateway;

import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.config.StarShowCommonAutoConfiguration;
import com.starshow.common.config.StarShowServletWebAutoConfiguration;
import com.starshow.common.security.SecuritySecretValidator;
import com.starshow.gateway.config.GatewaySecurityValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实证 H-1 与 ISSUE-002：
 * 1. WebFlux 网关上下文加载 JwtTokenProvider 且零 Servlet Bean 污染；
 * 2. 缺失或使用公开占位密钥时 GatewaySecurityValidator 拒绝启动且报错仅含配置项名。
 */
class GatewayAutoConfigurationIsolationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    StarShowCommonAutoConfiguration.class,
                    StarShowServletWebAutoConfiguration.class
            ))
            .withUserConfiguration(GatewaySecurityValidator.class);

    @Test
    void shouldLoadCommonJwtBeanWithoutServletBeansWhenValidSecretsProvided() {
        contextRunner
                .withPropertyValues(
                        "starshow.security.jwt-secret=gw-test-jwt-secret-key-9f8e7d6c5b4a3f2e1d0c",
                        "starshow.security.internal-gateway-secret=gw-test-internal-sec-7f8e9d6c5b4a"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtTokenProvider.class);
                    assertThat(context).doesNotHaveBean(StarShowServletWebAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean("traceIdFilter");
                    assertThat(context).doesNotHaveBean("authContextServletFilter");
                    assertThat(context).doesNotHaveBean("globalExceptionHandler");
                });
    }

    @Test
    void shouldFailStartupWhenGatewaySecretsAreMissingOrPublicPlaceholder() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(SecuritySecretValidator.PROP_JWT_SECRET);
        });

        contextRunner
                .withPropertyValues(
                        "starshow.security.jwt-secret=gw-test-jwt-secret-key-9f8e7d6c5b4a3f2e1d0c",
                        "starshow.security.internal-gateway-secret=starshow-dev-internal-gateway-secret"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(SecuritySecretValidator.PROP_INTERNAL_GATEWAY_SECRET);
                });
    }
}
