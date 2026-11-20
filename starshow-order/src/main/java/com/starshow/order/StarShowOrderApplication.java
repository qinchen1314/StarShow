package com.starshow.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.starshow.order", "com.starshow.common"})
public class StarShowOrderApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(StarShowOrderApplication.class);
        // 独立启动时自动加载 application-order.yml，避免与 starshow-boot-all 主 application.yml 冲突
        app.setAdditionalProfiles("order");
        app.run(args);
    }
}
