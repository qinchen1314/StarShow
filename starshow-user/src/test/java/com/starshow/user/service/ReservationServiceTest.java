package com.starshow.user.service;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.event.ShowRemindEvent;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.mq.ShowRemindConsumer;
import com.starshow.user.mq.ShowRemindDeliveryService;
import com.starshow.user.mq.ShowRemindNotifier;
import com.starshow.user.mq.ShowRemindProducer;
import com.starshow.user.mq.ShowRemindRecoveryScheduler;
import com.starshow.user.vo.MyReservationVO;
import com.starshow.user.vo.ReservationCountVO;
import com.starshow.user.vo.ReservationVO;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private UserFacade userFacade;
    @Mock
    private ShowReadFacade showReadFacade;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBloomFilter<Long> bloomFilter;
    @Mock
    private ReservationCountStore reservationCountStore;
    @Mock
    private ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private ReservationProperties properties;
    private ShowRemindProducer showRemindProducer;
    private ShowRemindRecoveryScheduler recoveryScheduler;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        properties = new ReservationProperties();
        properties.setMqEnabled(true);
        properties.setRecoveryEnabled(true);
        properties.setRemindLeadSeconds(600L);
        properties.setRemindMaxDelaySeconds(259200L);
        properties.setRemindRetryBackoffSeconds(30L);

        showRemindProducer = new ShowRemindProducer(rocketMQTemplateProvider, reservationMapper, properties);
        recoveryScheduler = new ShowRemindRecoveryScheduler(
                reservationMapper, showReadFacade, showRemindProducer, properties
        );
        reservationService = new ReservationService(
                userFacade,
                showReadFacade,
                reservationMapper,
                redissonClient,
                reservationCountStore,
                new GeneSnowflakeIdGenerator(1),
                showRemindProducer,
                properties
        );
    }

    @Test
    void shouldRejectUnverifiedUserWithA0104() {
        when(userFacade.isRealNameVerified(10001L)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> reservationService.reserve(10001L, 10003L));
        assertEquals(ErrorCode.IDENTITY_REQUIRED, ex.getErrorCode());
        verify(reservationMapper, never()).insert(any(ReservationDO.class));
    }

    @Test
    void shouldAllowNormalUserWhenBloomFilterHasFalsePositive() {
        long userId = 10001L;
        long showId = 10003L;
        ShowBasicDTO show = futureShow(showId, 12);

        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(showReadFacade.getShowBasic(showId)).thenReturn(show);
        doReturn(bloomFilter).when(redissonClient).getBloomFilter(RedisKeys.reservationBloom(showId));
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(false);
        when(bloomFilter.contains(userId)).thenReturn(true);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(reservationCountStore.captureEpochBeforeInsert(showId)).thenReturn("epoch-1");
        when(reservationCountStore.incrementAfterDbInsert(showId, "epoch-1")).thenReturn(2L);
        when(rocketMQTemplateProvider.getIfAvailable()).thenReturn(rocketMQTemplate);
        SendResult okResult = new SendResult();
        okResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), any(Message.class), anyLong()))
                .thenReturn(okResult);

        ReservationVO vo = reservationService.reserve(userId, showId);
        assertNotNull(vo.getReservationId());
        assertEquals(showId, vo.getShowId());
        assertEquals(2L, vo.getCurrentCount());
        assertTrue(vo.isRemindScheduled());

        verify(reservationMapper).insert(any(ReservationDO.class));
        verify(reservationMapper).casMarkScheduledForVersion(vo.getReservationId(), 1);
        verify(bloomFilter).add(userId);
        verify(rocketMQTemplate).syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), any(Message.class), anyLong());
    }

    @Test
    void shouldRejectTrueDuplicateReservationWithA0106WithoutIncrementingOrSendingMq() {
        long userId = 10001L;
        long showId = 10003L;
        ShowBasicDTO show = futureShow(showId, 12);

        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(showReadFacade.getShowBasic(showId)).thenReturn(show);
        doReturn(bloomFilter).when(redissonClient).getBloomFilter(RedisKeys.reservationBloom(showId));
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(false);
        when(bloomFilter.contains(userId)).thenReturn(true);
        when(reservationMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> reservationService.reserve(userId, showId));
        assertEquals(ErrorCode.RESERVATION_DUPLICATE, ex.getErrorCode());

        verify(reservationMapper, never()).insert(any(ReservationDO.class));
        verify(reservationCountStore, never()).incrementAfterDbInsert(anyLong(), any());
        verify(rocketMQTemplate, never()).syncSendDeliverTimeMills(anyString(), any(Message.class), anyLong());
    }

    @Test
    void shouldRejectConcurrentDuplicateViaDbUniqueConstraintWithA0106() {
        long userId = 10001L;
        long showId = 10003L;
        ShowBasicDTO show = futureShow(showId, 12);

        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(showReadFacade.getShowBasic(showId)).thenReturn(show);
        doReturn(bloomFilter).when(redissonClient).getBloomFilter(RedisKeys.reservationBloom(showId));
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(false);
        when(bloomFilter.contains(userId)).thenReturn(false);
        when(reservationMapper.insert(any(ReservationDO.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_user_show'"));

        BizException ex = assertThrows(BizException.class, () -> reservationService.reserve(userId, showId));
        assertEquals(ErrorCode.RESERVATION_DUPLICATE, ex.getErrorCode());
        verify(reservationCountStore, never()).incrementAfterDbInsert(anyLong(), any());
    }

    @Test
    void shouldDegradeToCasRemindStatus2WithBackoffAndRecoverWhenBrokerReturns() {
        long userId = 10002L;
        long showId = 10003L;
        ShowBasicDTO show = futureShow(showId, 12);

        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(showReadFacade.getShowBasic(showId)).thenReturn(show);
        doReturn(bloomFilter).when(redissonClient).getBloomFilter(RedisKeys.reservationBloom(showId));
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(false);
        when(bloomFilter.contains(userId)).thenReturn(false);
        when(reservationCountStore.captureEpochBeforeInsert(showId)).thenReturn("epoch-2");
        when(reservationCountStore.incrementAfterDbInsert(showId, "epoch-2")).thenReturn(5L);
        when(rocketMQTemplateProvider.getIfAvailable()).thenReturn(rocketMQTemplate);
        when(rocketMQTemplate.syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), any(Message.class), anyLong()))
                .thenThrow(new RuntimeException("Broker timeout"));

        ReservationVO vo = reservationService.reserve(userId, showId);
        assertNotNull(vo.getReservationId());
        assertEquals(5L, vo.getCurrentCount());
        assertFalse(vo.isRemindScheduled());

        verify(reservationMapper).insert(any(ReservationDO.class));
        verify(reservationMapper).casMarkFailedWithRetryForVersion(eq(vo.getReservationId()), eq(1), any(LocalDateTime.class));

        // 模拟 Broker 恢复后，ShowRemindRecoveryScheduler 扫描到到期 status=2 记录并成功补投 -> CAS 置 3
        SendResult okResult = new SendResult();
        okResult.setSendStatus(SendStatus.SEND_OK);
        reset(rocketMQTemplate);
        when(rocketMQTemplate.syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), any(Message.class), anyLong()))
                .thenReturn(okResult);
        when(reservationMapper.selectDueRecoverableReminders(any(LocalDateTime.class), eq(0L), eq(100)))
                .thenReturn(List.of(
                        ReservationDO.builder()
                                .id(vo.getReservationId())
                                .userId(userId)
                                .showId(showId)
                                .remindStatus(ReservationDO.REMIND_FAILED)
                                .remindConfigVersion(1)
                                .build()
                ));

        int recovered = recoveryScheduler.recoverBatch(100);
        assertEquals(1, recovered);
        verify(reservationMapper).casMarkScheduledForVersion(vo.getReservationId(), 1);
    }

    @Test
    void shouldDeferNextRetryTimeWhenExceeding72hAndIsolateSingleShowMetadataFailure() {
        long userId = 10003L;
        long farShowId = 10003L;
        ShowBasicDTO farShow = futureShow(farShowId, 240);

        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(showReadFacade.getShowBasic(farShowId)).thenReturn(farShow);
        doReturn(bloomFilter).when(redissonClient).getBloomFilter(RedisKeys.reservationBloom(farShowId));
        when(bloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(false);
        when(bloomFilter.contains(userId)).thenReturn(false);
        when(reservationCountStore.captureEpochBeforeInsert(farShowId)).thenReturn("epoch-3");
        when(reservationCountStore.incrementAfterDbInsert(farShowId, "epoch-3")).thenReturn(3L);

        ReservationVO vo = reservationService.reserve(userId, farShowId);
        assertFalse(vo.isRemindScheduled());
        verify(rocketMQTemplate, never()).syncSendDeliverTimeMills(anyString(), any(Message.class), anyLong());
        verify(reservationMapper).casDeferNextRetryTimeForVersion(eq(vo.getReservationId()), eq(1), any(LocalDateTime.class));

        // ISSUE-009 隔离测试：批次内场次 20001 查询元数据抛异常，同一批内场次 20002 仍必须正常补投！
        long brokenShowId = 20001L;
        long healthyShowId = 20002L;
        when(showReadFacade.getShowBasic(brokenShowId)).thenThrow(new RuntimeException("Show service 503"));
        when(showReadFacade.getShowBasic(healthyShowId)).thenReturn(futureShow(healthyShowId, 12));
        when(rocketMQTemplateProvider.getIfAvailable()).thenReturn(rocketMQTemplate);
        SendResult okResult = new SendResult();
        okResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), any(Message.class), anyLong()))
                .thenReturn(okResult);
        when(reservationMapper.selectDueRecoverableReminders(any(LocalDateTime.class), eq(0L), eq(10)))
                .thenReturn(List.of(
                        ReservationDO.builder().id(101L).userId(1L).showId(brokenShowId).remindStatus(2).remindConfigVersion(1).build(),
                        ReservationDO.builder().id(102L).userId(2L).showId(healthyShowId).remindStatus(2).remindConfigVersion(1).build()
                ));

        int recovered = recoveryScheduler.recoverDueReminders(10, 5);
        assertEquals(1, recovered);
        verify(reservationMapper).casDeferNextRetryTimeForVersion(eq(101L), eq(1), any(LocalDateTime.class));
        verify(reservationMapper).casMarkScheduledForVersion(102L, 1);
    }

    @Test
    void shouldNotifyExactlyOnceWhenConsumerReceivesDuplicateOrRecoveryMessages() {
        AtomicInteger notifyCount = new AtomicInteger(0);
        ShowRemindNotifier notifier = event -> notifyCount.incrementAndGet();
        UserNotificationService notificationService = mock(UserNotificationService.class);
        ShowRemindDeliveryService deliveryService = new ShowRemindDeliveryService(reservationMapper, notificationService);
        ShowRemindConsumer consumer = new ShowRemindConsumer(deliveryService, showReadFacade, notifier);
        ShowRemindEvent event = ShowRemindEvent.builder()
                .reservationId(50001L)
                .userId(10001L)
                .showId(10003L)
                .showTitle("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().plusHours(1))
                .saleConfigVersion(1)
                .traceId("trace-123")
                .build();

        when(showReadFacade.getShowBasic(10003L)).thenReturn(futureShow(10003L, 1));
        when(reservationMapper.casMarkDeliveredForVersion(50001L, 1)).thenReturn(1, 0, 0);

        consumer.onMessage(event);
        consumer.onMessage(event);
        consumer.onMessage(event);

        verify(reservationMapper, times(3)).casMarkDeliveredForVersion(50001L, 1);
        verify(notificationService, times(1)).recordReminderNotification(event);
        assertEquals(1, notifyCount.get(), "Exactly one simulated notification must be emitted");
    }

    @Test
    void shouldDropStaleVersionReminderMessageWhenShowHasBeenRescheduled() {
        AtomicInteger notifyCount = new AtomicInteger(0);
        ShowRemindNotifier notifier = event -> notifyCount.incrementAndGet();
        UserNotificationService notificationService = mock(UserNotificationService.class);
        ShowRemindDeliveryService deliveryService = new ShowRemindDeliveryService(reservationMapper, notificationService);
        ShowRemindConsumer consumer = new ShowRemindConsumer(deliveryService, showReadFacade, notifier);

        ShowBasicDTO rescheduledShow = ShowBasicDTO.builder()
                .showId(10003L)
                .title("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().plusDays(2))
                .saleEndTime(LocalDateTime.now().plusDays(10))
                .saleStatus("INIT")
                .saleConfigVersion(2)
                .build();
        when(showReadFacade.getShowBasic(10003L)).thenReturn(rescheduledShow);

        ShowRemindEvent staleV1Event = ShowRemindEvent.builder()
                .reservationId(50009L)
                .userId(10001L)
                .showId(10003L)
                .showTitle("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().plusHours(1))
                .saleConfigVersion(1)
                .traceId("stale-v1")
                .build();

        consumer.onMessage(staleV1Event);

        verify(reservationMapper, never()).casMarkDeliveredForVersion(anyLong(), anyInt());
        verify(notificationService, never()).recordReminderNotification(any());
        assertEquals(0, notifyCount.get(), "Stale schedule version reminder must be skipped silently");
    }

    @Test
    void shouldMarkAlreadyOnSaleReservationAsSkippedStatus4() {
        long showId = 10001L;
        ShowBasicDTO onSaleShow = ShowBasicDTO.builder()
                .showId(showId)
                .title("已开售演示场次")
                .saleStartTime(LocalDateTime.now().minusMinutes(5))
                .saleEndTime(LocalDateTime.now().plusDays(5))
                .saleStatus("ON_SALE")
                .saleConfigVersion(1)
                .build();

        ShowRemindProducer.ScheduleOutcome outcome = showRemindProducer.scheduleRemindWithOutcome(70001L, 10001L, onSaleShow);
        assertEquals(ShowRemindProducer.ScheduleOutcome.SKIPPED_ALREADY_ON_SALE, outcome);
        verify(reservationMapper).casMarkSkipped(70001L);
        verify(reservationMapper, never()).casMarkReminded(anyLong());
        verify(rocketMQTemplate, never()).syncSendDeliverTimeMills(anyString(), any(Message.class), anyLong());
    }

    @Test
    void shouldReturnMyReservationStatusAndNextActionAcrossLifecycle() {
        long userId = 10001L;
        long showId = 10003L;
        ShowBasicDTO upcoming = futureShow(showId, 12);
        upcoming.setSaleConfigVersion(2);

        when(showReadFacade.getShowBasic(showId)).thenReturn(upcoming);
        when(reservationMapper.selectByUserAndShow(userId, showId)).thenReturn(null);
        // 未实名用户前置引导 VERIFY_IDENTITY + 返回 serverTime 校准倒计时
        when(userFacade.isRealNameVerified(userId)).thenReturn(false);
        MyReservationVO unverified = reservationService.getMyReservation(userId, showId);
        assertFalse(unverified.isRealNameVerified());
        assertEquals("VERIFY_IDENTITY", unverified.getNextAction());
        assertNotNull(unverified.getServerTime());

        // 已实名用户未预约 -> RESERVE
        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        MyReservationVO before = reservationService.getMyReservation(userId, showId);
        assertFalse(before.isReserved());
        assertTrue(before.isRealNameVerified());
        assertEquals("RESERVE", before.getNextAction());
        assertEquals("NOT_RESERVED", before.getRemindStatusLabel());
        assertEquals(2, before.getSaleConfigVersion());
        assertFalse(before.isEntryOpen());

        when(reservationMapper.selectByUserAndShow(userId, showId)).thenReturn(
                ReservationDO.builder()
                        .id(88801L)
                        .userId(userId)
                        .showId(showId)
                        .remindStatus(ReservationDO.REMIND_SCHEDULED)
                        .remindConfigVersion(2)
                        .build()
        );
        MyReservationVO reserved = reservationService.getMyReservation(userId, showId);
        assertTrue(reserved.isReserved());
        assertEquals(88801L, reserved.getReservationId());
        assertEquals("WAIT_FOR_SALE", reserved.getNextAction());
        assertEquals("SCHEDULED", reserved.getRemindStatusLabel());
        assertFalse(reserved.isEntryOpen());

        upcoming.setSaleStartTime(LocalDateTime.now().minusMinutes(1));
        upcoming.setSaleStatus("ON_SALE");
        MyReservationVO onSale = reservationService.getMyReservation(userId, showId);
        assertTrue(onSale.isEntryOpen());
        assertEquals("ENTER_SECKILL", onSale.getNextAction());
    }

    @Test
    void shouldListMyReservationsSortedBySoonestSaleFirstAndEndedLast() {
        long userId = 10001L;
        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(reservationMapper.selectByUserId(userId, 20)).thenReturn(List.of(
                ReservationDO.builder().id(1L).userId(userId).showId(30001L).remindStatus(0).remindConfigVersion(1).build(),
                ReservationDO.builder().id(2L).userId(userId).showId(30002L).remindStatus(3).remindConfigVersion(1).build(),
                ReservationDO.builder().id(3L).userId(userId).showId(30003L).remindStatus(4).remindConfigVersion(1).build()
        ));
        when(showReadFacade.getShowBasic(30001L)).thenReturn(futureShow(30001L, 48));
        when(showReadFacade.getShowBasic(30002L)).thenReturn(futureShow(30002L, 2));
        when(showReadFacade.getShowBasic(30003L)).thenReturn(ShowBasicDTO.builder()
                .showId(30003L)
                .title("已结束专场")
                .saleStartTime(LocalDateTime.now().minusDays(2))
                .saleEndTime(LocalDateTime.now().minusDays(1))
                .saleStatus("ENDED")
                .saleConfigVersion(1)
                .build());

        List<MyReservationVO> list = reservationService.listMyReservations(userId, 20);
        assertEquals(3, list.size());
        assertEquals(30002L, list.get(0).getShowId(), "Soonest upcoming show must appear first");
        assertEquals(30001L, list.get(1).getShowId());
        assertEquals(30003L, list.get(2).getShowId(), "Ended show must appear last");
        assertEquals("SALE_ENDED", list.get(2).getNextAction());
    }

    @Test
    void shouldDelegateReservationCountReadToConsistentCountStore() {
        long showId = 10003L;
        long nowMs = System.currentTimeMillis();
        when(reservationCountStore.getCountSnapshot(showId))
                .thenReturn(new ReservationCountStore.CountSnapshot(42L, nowMs, false, true));

        ReservationCountVO vo = reservationService.getReservationCount(showId);
        assertEquals(showId, vo.getShowId());
        assertEquals(42L, vo.getCount());
        assertEquals(nowMs, vo.getAsOfEpochMs());
        assertFalse(vo.isStale());
        assertTrue(vo.isCountAvailable());
    }

    private ShowBasicDTO futureShow(long showId, long hoursUntilSale) {
        LocalDateTime start = LocalDateTime.now().plusHours(hoursUntilSale);
        return ShowBasicDTO.builder()
                .showId(showId)
                .title("星演跨年盛典")
                .saleStartTime(start)
                .saleEndTime(start.plusDays(15))
                .saleStatus("INIT")
                .saleConfigVersion(1)
                .build();
    }
}
