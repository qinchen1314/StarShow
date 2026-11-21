package com.starshow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.starshow.payment", "com.starshow.common"})
public class StarShowPaymentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StarShowPaymentApplication.class);
        application.setAdditionalProfiles("payment", "distributed");
        application.run(args);
    }
}
