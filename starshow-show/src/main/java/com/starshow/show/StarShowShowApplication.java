package com.starshow.show;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.starshow.show", "com.starshow.common"})
public class StarShowShowApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(StarShowShowApplication.class);
        // 独立启动时自动加载 application-show.yml，避免与 starshow-boot-all 主 application.yml 冲突
        app.setAdditionalProfiles("show");
        app.run(args);
    }
}
