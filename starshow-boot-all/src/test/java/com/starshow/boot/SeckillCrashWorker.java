package com.starshow.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.order.dto.SeckillOrderRequest;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.service.SeckillOrderService;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

final class SeckillCrashWorker {

    enum Cut { BOUND, DEDUCTED }

    public static void main(String[] args) throws Exception {
        Cut cut = Cut.valueOf(args[0]);
        Path marker = Path.of(args[1]);
        Path requestFile = Path.of(args[2]);
        long userId = Long.parseLong(args[3]);
        String schema = System.getenv("MYSQL_DATABASE");
        String redisDatabase = System.getenv("REDIS_DATABASE");
        if (schema == null || !schema.matches("starshow_(012|c3)_[a-zA-Z0-9_]+")
                || redisDatabase == null || !redisDatabase.matches("[1-9]|1[0-5]")) {
            throw new IllegalStateException("Isolated MySQL and Redis fixtures are required");
        }

        var app = new SpringApplication(StarShowBootAllApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("monolith");
        app.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) context ->
                context.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(observer(cut, marker))));
        try (var context = app.run(
                "--starshow.seckill.strategy=V4", "--starshow.seckill.request-lease-ms=1000",
                "--starshow.seckill.timeout-dispatch-enabled=false", "--starshow.seckill.pending-reconcile-enabled=false",
                "--starshow.reservation.mq-enabled=false", "--starshow.reservation.recovery-enabled=false",
                "--starshow.show.task-recovery-enabled=false", "--starshow.show.lifecycle-sync-enabled=false",
                "--starshow.show.bloom-enabled=false", "--starshow.payment.enabled=false",
                "--spring.datasource.hikari.minimum-idle=1", "--spring.datasource.hikari.maximum-pool-size=3",
                "--logging.level.root=WARN", "--knife4j.enable=false")) {
            SeckillOrderRequest request = new ObjectMapper().readValue(requestFile.toFile(), SeckillOrderRequest.class);
            context.getBean(SeckillOrderService.class).placeOrder(userId, request);
            throw new IllegalStateException("Expected worker to stop at " + cut);
        }
    }

    private static BeanPostProcessor observer(Cut cut, Path marker) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof OrderRedisScripts)) {
                    return bean;
                }
                ProxyFactory proxy = new ProxyFactory(bean);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    String method = invocation.getMethod().getName();
                    Object result = invocation.proceed();
                    if (cut == Cut.BOUND && "bindRequest".equals(method)
                            && result instanceof OrderRedisScripts.ReqBindResult binding
                            && "BOUND".equals(binding.code())) {
                        waitForExit(marker, binding.orderNo());
                    }
                    if (cut == Cut.DEDUCTED && "executeAutoDeduct".equals(method) && Long.valueOf(1L).equals(result)) {
                        waitForExit(marker, (long) invocation.getArguments()[4]);
                    }
                    return result;
                });
                return proxy.getProxy();
            }
        };
    }

    private static void waitForExit(Path marker, long orderNo) throws Exception {
        Files.writeString(marker, Long.toString(orderNo));
        new CountDownLatch(1).await();
    }
}
