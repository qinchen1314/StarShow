package com.starshow.show;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowCommonAutoConfiguration;
import com.starshow.common.config.StarShowServletWebAutoConfiguration;
import com.starshow.common.security.SecuritySecretValidator;
import com.starshow.common.web.GlobalExceptionHandler;
import com.starshow.common.web.ValidationExceptionHandler;
import com.starshow.show.config.ShowSecurityValidator;
import com.starshow.show.controller.InternalShowController;
import com.starshow.show.service.ShowScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实证 ISSUE-002 与 ISSUE-006：
 * 1. 独立演出服务模块能够完整加载 GlobalExceptionHandler、ValidationExceptionHandler 与 InternalShowController，
 *    无需 jwt-secret，仅需独立的 internal-gateway-secret；
 * 2. 缺失或使用公开占位 internal-gateway-secret 时拒绝启动。
 */
class ShowServiceStartupAndSecurityTest {

    private static final String SHOW_INTERNAL_SECRET = "show-svc-internal-sec-7f8e9d6c5b4a";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    StarShowCommonAutoConfiguration.class,
                    StarShowServletWebAutoConfiguration.class
            ))
            .withUserConfiguration(ShowSecurityValidator.class, InternalShowController.class)
            .withBean(ShowScheduleService.class, () -> mock(ShowScheduleService.class))
            .withBean(ShowReadFacade.class, () -> {
                ShowReadFacade facade = mock(ShowReadFacade.class);
                when(facade.getShowBasic(10003L)).thenReturn(ShowBasicDTO.builder()
                        .showId(10003L)
                        .title("星演跨年盛典")
                        .saleStartTime(LocalDateTime.now().plusHours(12))
                        .saleEndTime(LocalDateTime.now().plusDays(10))
                        .saleStatus("INIT")
                        .build());
                return facade;
            });

    @Test
    void shouldStartShowWebContextWithValidationAndServeInternalShowEndpoint() {
        contextRunner
                .withPropertyValues("starshow.security.internal-gateway-secret=" + SHOW_INTERNAL_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(ValidationExceptionHandler.class);
                    assertThat(context).hasSingleBean(InternalShowController.class);

                    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mockMvc.perform(get("/internal/shows/10003/basic")
                                    .header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, SHOW_INTERNAL_SECRET))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.code").value("00000"))
                            .andExpect(jsonPath("$.data.showId").value(10003));

                    // 未携带内部密钥必须被拒绝
                    mockMvc.perform(get("/internal/shows/10003/basic"))
                            .andExpect(status().isUnauthorized())
                            .andExpect(jsonPath("$.code").value("A0101"));
                });
    }

    @Test
    void shouldFailStartupWhenShowInternalSecretMissingOrPlaceholder() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(SecuritySecretValidator.PROP_INTERNAL_GATEWAY_SECRET);
        });

        contextRunner
                .withPropertyValues("starshow.security.internal-gateway-secret=starshow-dev-internal-gateway-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(SecuritySecretValidator.PROP_INTERNAL_GATEWAY_SECRET);
                });
    }
}
