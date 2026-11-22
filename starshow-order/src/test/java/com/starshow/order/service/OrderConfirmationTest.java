package com.starshow.order.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.starshow.common.auth.LoginUser;
import com.starshow.common.auth.UserContext;
import com.starshow.common.config.JacksonLongToStringConfig;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.controller.OrderController;
import com.starshow.order.controller.SeckillController;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderConfirmationTest {
    @ParameterizedTest
    @CsvSource({"-1, PAY", "0, CHECK_PAYMENT", "1, CHECK_PAYMENT"})
    void allRecoveryRoutesUseTheSameServerClockAndPaymentBoundary(long offsetMs, String nextAction) throws Exception {
        long userId = 33;
        long orderNo = 9007199254741009L;
        long showId = 10001;
        String requestId = "confirmation-fixture";
        var zone = ZoneId.systemDefault();
        var expires = LocalDateTime.of(2026, 11, 22, 14, 0);
        var deadline = expires.minusSeconds(30).atZone(zone).toOffsetDateTime();
        var now = deadline.plusNanos(offsetMs * 1_000_000);
        var order = OrderDO.builder().orderNo(orderNo).showId(showId).userId(userId).status("WAIT_PAY")
                .expireTime(expires).build();
        var rows = mock(OrderShardMapper.class);
        var table = OrderShardTableResolver.tableForUserId(userId);
        when(rows.selectByOrderNo(table, orderNo)).thenReturn(order);
        when(rows.selectActiveByUserShow(table, userId, showId)).thenReturn(order);
        when(rows.selectByUserShowRequest(table, userId, showId, requestId)).thenReturn(order);
        var query = new OrderQueryService(rows, mock(OrderActionMapper.class), mock(OrderRedisScripts.class),
                new SeckillProperties(), new OrderConfirmation(Clock.fixed(now.toInstant(), zone), 30));
        var jackson = Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new JacksonLongToStringConfig().longToStringCustomizer().customize(jackson);
        var mvc = MockMvcBuilders.standaloneSetup(new OrderController(query), new SeckillController(null, null, query))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(jackson.build())).build();
        try {
            UserContext.set(new LoginUser(userId, "confirmation-fixture", Long.MAX_VALUE));
            for (var path : new String[]{"/api/v1/orders/" + orderNo + "/status", "/api/v1/orders/current",
                    "/api/v1/seckill/requests/" + requestId}) {
                mvc.perform(get(path).param("showId", String.valueOf(showId)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("WAIT_PAY"))
                        .andExpect(jsonPath("$.data.orderNo").value(String.valueOf(orderNo)))
                        .andExpect(jsonPath("$.data.serverTime").value(format(now)))
                        .andExpect(jsonPath("$.data.paymentCreateDeadline").value(format(deadline)))
                        .andExpect(jsonPath("$.data.nextAction").value(nextAction))
                        .andExpect(jsonPath("$.data.canStartNew").value(false));
            }
        } finally {
            UserContext.clear();
        }
    }

    private static String format(OffsetDateTime time) {
        return time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
