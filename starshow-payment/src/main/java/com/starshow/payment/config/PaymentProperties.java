package com.starshow.payment.config;

import com.starshow.common.security.SecuritySecretValidator;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
@ConfigurationProperties("starshow.payment")
public class PaymentProperties {
    private long channelExpireSeconds = 870;
    private long closeGuardSeconds = 30;
    private long signatureSkewSeconds = 300;
    @ToString.Exclude
    private String callbackSecret;
    private boolean mockEnabled;
    private boolean faultEnabled;

    @PostConstruct
    void validate() {
        SecuritySecretValidator.validateSigningSecret(callbackSecret, "starshow.payment.callback-secret");
        if (channelExpireSeconds <= 0 || closeGuardSeconds < 1 || signatureSkewSeconds < 1) {
            throw new IllegalStateException("Invalid payment time configuration");
        }
    }
}
