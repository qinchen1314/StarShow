package com.starshow.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.OrderMetrics;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.dto.SeckillOrderRequest;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mq.OrderCreateTransactionListener;
import com.starshow.order.vo.CurrentShowOrderVO;
import com.starshow.order.vo.OrderStatusVO;
import com.starshow.order.vo.SeckillOrderVO;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillOrderAndQueryUnitTest {

    private UserFacade userFacade;
    private ShowReadFacade showReadFacade;
    private OrderShardMapper orderShardMapper;
    private OrderActionMapper orderActionMapper;
    private OrderRedisScripts redisScripts;
    private GeneSnowflakeIdGenerator idGenerator;
    private SeckillProperties properties;
    private SeckillOrderService orderService;
    private OrderQueryService queryService;

    @BeforeEach
    void setUp() {
        userFacade = mock(UserFacade.class);
        showReadFacade = mock(ShowReadFacade.class);
        orderShardMapper = mock(OrderShardMapper.class);
        orderActionMapper = mock(OrderActionMapper.class);
        redisScripts = mock(OrderRedisScripts.class);
        idGenerator = new GeneSnowflakeIdGenerator(3L);
        properties = new SeckillProperties();
        properties.setStrategy("V4");

        SeckillStrategy mockV4 = new StubV4Strategy();

        orderService = new SeckillOrderService(
                userFacade,
                showReadFacade,
                new SeatSelectionValidator(),
                orderShardMapper,
                redisScripts,
                idGenerator,
                properties,
                List.of(mockV4)
        );
        queryService = new OrderQueryService(orderShardMapper, orderActionMapper, redisScripts, properties);
    }

    @Test
    void shouldRejectPriceChangeWithA0308AndCurrentUnitPrice() {
        when(userFacade.isRealNameVerified(1001L)).thenReturn(true);
        when(showReadFacade.getTierSnapshot(10001L, 20001L)).thenReturn(
                TierSnapshotDTO.builder()
                        .showId(10001L)
                        .tierId(20001L)
                        .priceCent(168000L)
                        .saleStatus("ON_SALE")
                        .seatMode("AUTO_ALLOC")
                        .saleStartTime(LocalDateTime.now().minusMinutes(5))
                        .saleEndTime(LocalDateTime.now().plusHours(2))
                        .saleConfigVersion(1)
                        .maxTicketsPerOrder(2)
                        .build()
        );

        SeckillOrderRequest req = baseAutoReq("req-price-0001", 158000L);
        BizException ex = assertThrows(BizException.class, () -> orderService.placeOrder(1001L, req));
        assertEquals(ErrorCode.PRICE_CHANGED, ex.getErrorCode());
        assertInstanceOf(Map.class, ex.getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ex.getData();
        assertEquals("168000", data.get("currentUnitPriceCent"));
        assertEquals("158000", data.get("expectedUnitPriceCent"));
    }

    @Test
    void shouldRecoverPersistedOrderOnReplayRegardlessOfTokenOrPriceAndRejectTamperedFingerprintA0307() {
        long userId = 1002L;
        long orderNo = idGenerator.nextOrderId(userId);
        SeckillOrderRequest originalReq = baseAutoReq("req-replay-0001", 168000L);
        String fp = SeckillRequestFingerprint.compute(userId, originalReq);

        OrderDO persisted = OrderDO.builder()
                .orderNo(orderNo)
                .userId(userId)
                .showId(10001L)
                .tierId(20001L)
                .qty(1)
                .amountCent(168000L)
                .status("WAIT_PAY")
                .requestId("req-replay-0001")
                .requestFingerprint(fp)
                .seckillStrategy("V4")
                .expireTime(LocalDateTime.now().plusMinutes(15))
                .build();

        when(orderShardMapper.selectByUserShowRequest(anyString(), eq(userId), eq(10001L), eq("req-replay-0001")))
                .thenReturn(persisted);

        // 即使 Token 换了/过期了、也不再查 ShowReadFacade 价格或窗口，直接找回原单号
        originalReq.setSeckillToken("expired-token");
        SeckillOrderVO recovered = orderService.placeOrder(userId, originalReq);
        assertEquals(orderNo, recovered.getOrderNo());
        assertEquals("WAIT_PAY", recovered.getStatus());

        // 同 clientRequestId 改参数（例如改 qty=2） -> A0307
        SeckillOrderRequest tampered = baseAutoReq("req-replay-0001", 168000L);
        tampered.setQty(2);
        BizException conflict = assertThrows(BizException.class, () -> orderService.placeOrder(userId, tampered));
        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.getErrorCode());
    }

    @Test
    void shouldRejectCrossUserOrderAccessWithA0304AndSupportCrossDeviceCurrentOrderLookup() {
        long ownerId = 2001L;
        long attackerId = 2002L;
        long orderNo = idGenerator.nextOrderId(ownerId);

        OrderDO ownerOrder = OrderDO.builder()
                .orderNo(orderNo)
                .userId(ownerId)
                .showId(10001L)
                .tierId(20001L)
                .qty(1)
                .amountCent(168000L)
                .status("WAIT_PAY")
                .expireTime(LocalDateTime.now().plusMinutes(15))
                .createTime(LocalDateTime.now())
                .build();

        when(orderShardMapper.selectByOrderNo(anyString(), eq(orderNo))).thenReturn(ownerOrder);

        BizException detailEx = assertThrows(BizException.class, () -> queryService.getOrderDetail(attackerId, orderNo));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, detailEx.getErrorCode());

        BizException statusEx = assertThrows(BizException.class, () -> queryService.getOrderStatus(attackerId, orderNo, 10001L));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, statusEx.getErrorCode());

        // 跨设备恢复：DB 暂无但 Redis bought_owner 有在途单号 -> 返回 PENDING_CONFIRM
        long userB = 3005L;
        long inFlightOrderNo = idGenerator.nextOrderId(userB);
        when(orderShardMapper.selectActiveByUserShow(anyString(), eq(userB), eq(10001L))).thenReturn(null);
        when(orderShardMapper.selectByOrderNo(anyString(), eq(inFlightOrderNo))).thenReturn(null);
        when(redisScripts.readBoughtOwnerOrderNo(10001L, userB)).thenReturn(inFlightOrderNo);
        when(redisScripts.readDeductLog(10001L, inFlightOrderNo)).thenReturn(
                OrderRedisScripts.DeductLogRecord.builder()
                        .orderNo(inFlightOrderNo).userId(userB).tierId(20001L).qty(1).build()
        );
        when(redisScripts.hasRollbackDone(10001L, inFlightOrderNo)).thenReturn(false);

        CurrentShowOrderVO current = queryService.getCurrentShowOrder(userB, 10001L);
        assertTrue(current.isHasOrder());
        assertEquals(inFlightOrderNo, current.getOrderNo());
        assertEquals("PENDING_CONFIRM", current.getStatus());
        assertFalse(current.isRetryable());

        OrderStatusVO statusVO = queryService.getOrderStatus(userB, inFlightOrderNo, 10001L);
        assertEquals("PENDING_CONFIRM", statusVO.getStatus());
    }

    @Test
    void shouldReturnUnknownDuringTxCheckWhenRedisTransientlyFails() {
        OrderRedisScripts failingScripts = mock(OrderRedisScripts.class);
        when(failingScripts.executeTxCheck(eq(10001L), eq(999L), anyLong()))
                .thenThrow(new RuntimeException("Redis connection timeout"));

        OrderCreateTransactionListener listener = new OrderCreateTransactionListener(
                failingScripts,
                properties,
                new ObjectMapper(),
                new OrderMetrics(null)
        );

        RocketMQLocalTransactionState state = listener.checkByShowAndOrder(10001L, 999L);
        assertEquals(RocketMQLocalTransactionState.UNKNOWN, state,
                "Redis transient error during checkLocalTransaction must return UNKNOWN, never blind ROLLBACK");
    }

    @Test
    void shouldReturn503B0103WithBoundOrderNoWhenV4MqUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.apache.rocketmq.spring.core.RocketMQTemplate> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        LuaMqSeckillStrategy v4 = new LuaMqSeckillStrategy(emptyProvider, redisScripts, properties, new OrderMetrics(null));
        long orderNo = idGenerator.nextOrderId(4001L);
        SeckillExecutionContext ctx = SeckillExecutionContext.builder()
                .userId(4001L)
                .orderNo(orderNo)
                .request(baseAutoReq("req-v4-503-01", 168000L))
                .tier(TierSnapshotDTO.builder().showId(10001L).tierId(20001L).zoneId("VIP_A").build())
                .totalAmountCent(168000L)
                .strategyName("V4")
                .build();

        BizException ex = assertThrows(BizException.class, () -> v4.execute(ctx));
        assertEquals(ErrorCode.MQ_SEND_FAILED, ex.getErrorCode());
        assertEquals(503, ex.getHttpStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ex.getData();
        assertEquals(String.valueOf(orderNo), data.get("orderNo"));
        assertEquals("PENDING_CONFIRM", data.get("status"));
    }

    private SeckillOrderRequest baseAutoReq(String reqId, long expectedPrice) {
        return SeckillOrderRequest.builder()
                .showId(10001L)
                .tierId(20001L)
                .qty(1)
                .saleConfigVersion(1)
                .expectedUnitPriceCent(expectedPrice)
                .seckillToken("valid-token")
                .clientRequestId(reqId)
                .build();
    }

    private static final class StubV4Strategy implements SeckillStrategy {
        @Override
        public String name() {
            return "V4";
        }

        @Override
        public SeckillOrderVO execute(SeckillExecutionContext ctx) {
            return SeckillOrderVO.builder()
                    .orderNo(ctx.getOrderNo())
                    .showId(ctx.getRequest().getShowId())
                    .status("PENDING_CONFIRM")
                    .pollIntervalMs(500L)
                    .build();
        }
    }
}
