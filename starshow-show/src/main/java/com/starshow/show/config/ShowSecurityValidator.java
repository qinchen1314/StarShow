package com.starshow.show.config;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.security.SecuritySecretValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 演出服务启动期凭据强校验门禁（ISSUE-002）：
 * 校验服务间内部鉴权密钥，缺失或使用公开占位值时直接拒绝启动。
 */
@Component
@RequiredArgsConstructor
public class ShowSecurityValidator implements InitializingBean {

    private final StarShowSecurityProperties securityProperties;

    @Override
    public void afterPropertiesSet() {
        SecuritySecretValidator.validateInternalGatewaySecret(securityProperties.getInternalGatewaySecret());
    }
}
