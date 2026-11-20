package com.starshow.gateway.config;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.security.SecuritySecretValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 网关启动期凭据强校验门禁（ISSUE-002）：
 * 缺失、长度不足或使用公开占位值时直接拒绝启动。
 */
@Component
@RequiredArgsConstructor
public class GatewaySecurityValidator implements InitializingBean {

    private final StarShowSecurityProperties securityProperties;

    @Override
    public void afterPropertiesSet() {
        SecuritySecretValidator.validateJwtSecret(securityProperties.getJwtSecret());
        SecuritySecretValidator.validateInternalGatewaySecret(securityProperties.getInternalGatewaySecret());
    }
}
