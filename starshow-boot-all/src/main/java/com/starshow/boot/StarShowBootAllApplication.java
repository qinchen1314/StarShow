package com.starshow.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 本地极速开发单进程聚合启动器（聚合 user / show / order / payment）
 * 排除各子模块的独立 @SpringBootApplication 入口，避免上下文冲突。
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.starshow",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION,
                classes = SpringBootApplication.class
        )
)
public class StarShowBootAllApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarShowBootAllApplication.class, args);
    }
}
