package com.starshow.order.config;

import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.security.SecuritySecretValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 订单服务启动安全密钥门禁：
 * 独立微服务与聚合模式均必须配置非空、非公开占位的内部网关密钥，缺失或使用占位值时拒绝启动。
 */
@Component
@RequiredArgsConstructor
public class OrderSecurityValidator implements InitializingBean {

    private final StarShowSecurityProperties securityProperties;

    @Override
    public void afterPropertiesSet() {
        SecuritySecretValidator.validateInternalGatewaySecret(securityProperties.getInternalGatewaySecret());
    }
}
