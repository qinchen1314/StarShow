package com.starshow.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.auth.TokenBlacklistChecker;
import com.starshow.common.web.AuthContextServletFilter;
import com.starshow.common.web.GlobalExceptionHandler;
import com.starshow.common.web.TraceIdFilter;
import com.starshow.common.web.ValidationExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet Web 基础设施自动装配：
 * 严格使用 @ConditionalOnWebApplication(SERVLET) 隔离，绝不污染 WebFlux 网关 (starshow-gateway)。
 */
@AutoConfiguration(after = StarShowCommonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StarShowServletWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthContextServletFilter authContextServletFilter(
            ObjectProvider<JwtTokenProvider> jwtTokenProvider,
            StarShowSecurityProperties securityProperties,
            ObjectProvider<TokenBlacklistChecker> blacklistCheckerProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new AuthContextServletFilter(
                jwtTokenProvider.getIfAvailable(),
                securityProperties,
                blacklistCheckerProvider.getIfAvailable(),
                objectMapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
    static class ValidationHandlerAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ValidationExceptionHandler validationExceptionHandler() {
            return new ValidationExceptionHandler();
        }
    }
}
