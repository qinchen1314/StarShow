package com.starshow.common.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import java.math.BigInteger;

/**
 * 全局 Jackson 配置：
 * 将 64 位 Long / BigInteger 统一序列化为 String，
 * 防止前端 JS Number（最大安全整数 2^53-1）丢弃基因法 Snowflake 订单号尾部的分片基因位。
 */
@AutoConfiguration
@ConditionalOnClass(Jackson2ObjectMapperBuilderCustomizer.class)
public class JacksonLongToStringConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(BigInteger.class, ToStringSerializer.instance);
        };
    }
}
