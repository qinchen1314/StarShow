package com.starshow.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.starshow.user", "com.starshow.common"})
public class StarShowUserApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(StarShowUserApplication.class);
        // 独立启动时自动加载 application-user.yml，避免与 starshow-boot-all 主 application.yml 冲突
        app.setAdditionalProfiles("user");
        app.run(args);
    }
}
