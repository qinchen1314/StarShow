package com.starshow.boot;

import com.starshow.common.constant.RedisKeys;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.service.OrderClosingService;
import com.starshow.order.service.OrderFulfillmentService;
import com.starshow.order.service.OrderStockReleaseService;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.PaymentRepository;
import com.starshow.payment.repository.PaymentRepository.Refund;
import com.starshow.payment.service.MockPayChannel.Fault;
import com.starshow.payment.service.PaymentApplicationService;
import com.starshow.payment.service.RefundSagaService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;

final class RefundCrashWorker {
    enum Cut {
        REQUEST_RECORDED,
        TICKET_LOCKED,
        CHANNEL_REFUNDED,
        DB_STOCK_RELEASED,
        REDIS_STOCK_RELEASED,
        DONE_RECORDED,
        LATE_PAYMENT_CHANNEL_REFUNDED,
        RECOVERY
    }

    public static void main(String[] args) throws Exception {
        Cut cut = Cut.valueOf(args[0]);
        Path signal = Path.of(args[1]);
        var app = new SpringApplication(StarShowBootAllApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("monolith");
        if (cut != Cut.RECOVERY) {
            app.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) ctx ->
                    ctx.addBeanFactoryPostProcessor(factory -> {
                        var definitions = (BeanDefinitionRegistry) factory;
                        definitions.removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
                        factory.addBeanPostProcessor(cutObserver(cut, signal));
                    }));
        }
        try (var ctx = app.run(
                "--starshow.payment.enabled=true", "--starshow.payment.mock-enabled=true",
                "--starshow.payment.fault-enabled=true", "--starshow.payment.mq-enabled=false",
                "--starshow.payment.action-interval-ms=250", "--starshow.reservation.mq-enabled=false",
                "--starshow.reservation.recovery-enabled=false", "--starshow.show.task-recovery-enabled=false",
                "--starshow.show.lifecycle-sync-enabled=false", "--starshow.show.bloom-enabled=false",
                "--starshow.seckill.mq-enabled=false", "--starshow.seckill.timeout-dispatch-enabled=false",
                "--starshow.seckill.pending-reconcile-enabled=false", "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.datasource.hikari.minimum-idle=1", "--logging.level.root=WARN", "--knife4j.enable=false")) {
            if (cut == Cut.RECOVERY) pause(signal);
            exercise(ctx, cut);
            throw new IllegalStateException("Crash boundary was not reached: " + cut);
        }
    }

    private static BeanPostProcessor cutObserver(Cut cut, Path signal) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof PaymentRepository) return proxy(bean, invocation -> {
                    String method = invocation.getMethod().getName();
                    if ("advance".equals(method) && invocation.getArguments()[0] instanceof Refund refund) {
                        if (cut == Cut.TICKET_LOCKED && "LOCK_TICKET".equals(refund.sagaStep())
                                || (cut == Cut.CHANNEL_REFUNDED || cut == Cut.LATE_PAYMENT_CHANNEL_REFUNDED)
                                && "REFUND_PAY".equals(refund.sagaStep())) {
                            pause(signal);
                        }
                    }
                    Object result = invocation.proceed();
                    if (cut == Cut.REQUEST_RECORDED && "registerRefund".equals(method)
                            || cut == Cut.DONE_RECORDED && "advance".equals(method)
                            && "DONE".equals(invocation.getArguments()[1])) {
                        pause(signal);
                    }
                    return result;
                });
                if (bean instanceof OrderRedisScripts && (cut == Cut.DB_STOCK_RELEASED || cut == Cut.REDIS_STOCK_RELEASED)) {
                    return proxy(bean, invocation -> {
                        boolean release = "executeRollback".equals(invocation.getMethod().getName());
                        if (release && cut == Cut.DB_STOCK_RELEASED) pause(signal);
                        Object result = invocation.proceed();
                        if (release && cut == Cut.REDIS_STOCK_RELEASED && Long.valueOf(1).equals(result)) pause(signal);
                        return result;
                    });
                }
                return bean;
            }
        };
    }

    private static Object proxy(Object bean, MethodInterceptor advice) {
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return factory.getProxy();
    }

    private static void exercise(ConfigurableApplicationContext ctx, Cut cut) {
        Fixture fixture = Fixture.forCut(cut);
        var jdbc = ctx.getBean(JdbcTemplate.class);
        var redis = ctx.getBean(StringRedisTemplate.class);
        var payments = ctx.getBean(PaymentApplicationService.class);
        var order = ctx.getBean(OrderFulfillmentService.class);
        var refunds = ctx.getBean(RefundSagaService.class);
        var release = ctx.getBean(OrderStockReleaseService.class);
        seed(jdbc, redis, fixture);
        long payNo = payments.create(fixture.orderNo(), fixture.userId()).payNo();
        long refundNo;
        if (cut == Cut.LATE_PAYMENT_CHANNEL_REFUNDED) {
            payments.mockPay(payNo, fixture.userId(), Fault.RACE_WITH_CLOSE);
            var closing = ctx.getBean(OrderClosingService.class);
            require("CLOSED".equals(closing.cancel(fixture.orderNo(), fixture.userId()).orderStatus()), "Expected closed order");
            release.release(fixture.orderNo());
            payments.deliver(ctx.getBean(MockChannelRepository.class).get("PAY", payNo, false));
            refundNo = payments.get(payNo, fixture.userId()).abnormalRefundNo();
        } else {
            payments.mockPay(payNo, fixture.userId(), Fault.NONE);
            order.recoverIssue(fixture.orderNo());
            require("ISSUED".equals(order.ticketView(fixture.orderNo(), fixture.userId()).issueStatus()), "Expected issued ticket");
            refundNo = refunds.request(fixture.orderNo(), fixture.userId(), "Recovery fixture").refundNo();
        }
        for (int i = 0; i < 4; i++) refunds.advance(refundNo);
        release.release(fixture.orderNo());
        refunds.advance(refundNo);
    }

    private static void seed(JdbcTemplate jdbc, StringRedisTemplate redis, Fixture f) {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        require(schema != null && schema.matches("starshow_c4_[0-9a-f]{12}"), "Dedicated Phase 4 schema required");
        require(Integer.parseInt(System.getenv("REDIS_DATABASE")) > 0, "Isolated Redis DB required");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO t_show (id,title,city,venue,show_time,sale_start_time,sale_end_time,sale_status,seat_mode)
                VALUES (?, 'Refund recovery', 'Shanghai', 'Demo arena', ?, ?, ?, 'ON_SALE', 'MANUAL_SEAT')
                """, f.showId(), now.plusDays(1), now.minusMinutes(10), now.plusHours(2));
        jdbc.update("""
                INSERT INTO t_ticket_tier(id, show_id, tier_name, zone_id, price_cent, total_stock, available_stock)
                VALUES (?, ?, 'Floor', 'FLOOR', 68000, 1, 0)
                """, f.tierId(), f.showId());
        jdbc.update("""
                INSERT INTO t_seat(show_id, tier_id, zone_id, seat_index, row_no, col_no)
                VALUES (?, ?, 'FLOOR', 2, 1, 3)
                """, f.showId(), f.tierId());
        jdbc.update("INSERT INTO " + f.shard() + """
                 (order_no,user_id,show_id,tier_id,zone_id,seat_indices,amount_cent,status,expire_time)
                 VALUES (?, ?, ?, ?, 'FLOOR', '2', 68000, 'WAIT_PAY', ?)
                """, f.orderNo(), f.userId(), f.showId(), f.tierId(), now.plusMinutes(15));
        redis.opsForValue().set(RedisKeys.stock(f.showId(), f.tierId()), "0");
        redis.opsForValue().setBit(RedisKeys.seatBitmap(f.showId(), "FLOOR"), 2, true);
        redis.opsForSet().add(RedisKeys.boughtUsers(f.showId()), Long.toString(f.userId()));
        redis.opsForHash().put(RedisKeys.boughtOwner(f.showId()), Long.toString(f.userId()), Long.toString(f.orderNo()));
    }

    private static void pause(Path signal) throws Exception {
        Files.createFile(signal);
        new CountDownLatch(1).await();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Fixture(long orderNo, long userId, long showId, long tierId) {
        static Fixture forCut(Cut cut) {
            int i = cut.ordinal();
            long userId = 770000L + i;
            long orderNo = 960000L + i * 16L + (userId & 15L);
            long showId = 975000L + i * 2L;
            return new Fixture(orderNo, userId, showId, showId + 1);
        }
        String shard() { return OrderShardTableResolver.tableForOrderNo(orderNo); }
    }
}
