package com.starshow.show;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.starshow.show", "com.starshow.common"})
public class StarShowShowApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarShowShowApplication.class, args);
    }
}
