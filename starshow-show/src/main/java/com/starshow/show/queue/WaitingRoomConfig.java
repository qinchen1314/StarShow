package com.starshow.show.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WaitingRoomProperties.class)
class WaitingRoomConfig {
}
