package com.starshow.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.starshow.user", "com.starshow.common"})
public class StarShowUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarShowUserApplication.class, args);
    }
}
