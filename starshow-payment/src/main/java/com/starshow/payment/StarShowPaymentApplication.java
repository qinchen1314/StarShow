package com.starshow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.starshow.payment", "com.starshow.common"})
public class StarShowPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarShowPaymentApplication.class, args);
    }
}
