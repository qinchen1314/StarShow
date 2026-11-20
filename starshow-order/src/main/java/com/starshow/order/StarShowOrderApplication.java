package com.starshow.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.starshow.order", "com.starshow.common"})
public class StarShowOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarShowOrderApplication.class, args);
    }
}
