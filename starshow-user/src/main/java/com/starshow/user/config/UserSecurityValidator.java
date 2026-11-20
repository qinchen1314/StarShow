package com.starshow.user.config;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.security.SecuritySecretValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 用户域启动期凭据强校验门禁（ISSUE-002）：
 * 覆盖用户微服务与单进程聚合应用 (starshow-boot-all)，强制要求独立注入 4 项安全凭据。
 */
@Component
@RequiredArgsConstructor
public class UserSecurityValidator implements InitializingBean {

    private final StarShowSecurityProperties securityProperties;

    @Override
    public void afterPropertiesSet() {
        SecuritySecretValidator.validateJwtSecret(securityProperties.getJwtSecret());
        SecuritySecretValidator.validateInternalGatewaySecret(securityProperties.getInternalGatewaySecret());
        SecuritySecretValidator.validateAesKey256(securityProperties.getAesKey());
        SecuritySecretValidator.validateIdcardPepper(securityProperties.getIdcardPepper());
    }
}
