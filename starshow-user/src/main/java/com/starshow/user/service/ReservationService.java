package com.starshow.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.mq.ShowRemindProducer;
import com.starshow.user.vo.MyReservationVO;
import com.starshow.user.vo.ReservationCountVO;
import com.starshow.user.vo.ReservationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路一（节点 3 & 4）：演出场次预约蓄水服务
 * - 实名前置校验（UserFacade 正缓存）；
 * - 场次合法性校验（ShowReadFacade：monolith 本地直调 / distributed OpenFeign 代理）；
 * - Redisson 布隆过滤器初筛 + 命中时回查 DB 防 1% 误杀 + DB uk_user_show 物理并发兜底；
 * - 预约计数统一委托 ReservationCountStore（写前捕获 epoch + 单 Key Lua CAS-INCR + 两阶段 Fence 重建，零多计/零漏计）；
 * - 事务外投递 RocketMQ 5.x 任意时间延迟开票提醒（异常 CAS 降级置 2，由定时补偿恢复）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String SHOW_STATUS_ENDED = "ENDED";
    private static final Duration DEFAULT_BLOOM_TTL = Duration.ofDays(7);

    private final UserFacade userFacade;
    private final ShowReadFacade showReadFacade;
    private final ReservationMapper reservationMapper;
    private final RedissonClient redissonClient;
    private final ReservationCountStore reservationCountStore;
    private final GeneSnowflakeIdGenerator idGenerator;
    private final ShowRemindProducer showRemindProducer;
    private final ReservationProperties properties;

    public ReservationVO reserve(long userId, long showId) {
        // 1. 实名校验（未实名拦截 A0104）
        if (!userFacade.isRealNameVerified(userId)) {
            throw new BizException(ErrorCode.IDENTITY_REQUIRED);
        }

        // 2. 演出场次校验
        ShowBasicDTO show = showReadFacade.getShowBasic(showId);
        if (show == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }
        if (SHOW_STATUS_ENDED.equalsIgnoreCase(show.getSaleStatus())
                || (show.getSaleEndTime() != null && LocalDateTime.now().isAfter(show.getSaleEndTime()))) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE);
        }

        // 3. 布隆过滤器初筛 + 防 1% 误判二次确认（严禁把 contains==true 直接当成绝对存在）
        RBloomFilter<Long> bloom = getOrInitBloomFilter(showId, show.getSaleEndTime());
        if (bloom != null) {
            boolean maybeReserved = false;
            try {
                maybeReserved = bloom.contains(userId);
            } catch (Exception ex) {
                log.warn("Bloom filter contains check failed for showId={}, falling back to DB constraint", showId, ex);
            }
            if (maybeReserved && existsInDb(userId, showId)) {
                throw new BizException(ErrorCode.RESERVATION_DUPLICATE);
            }
        }

        // 4. 在 DB INSERT 之前捕获当前计数代际 epoch（防写后 INCR 与并发 GET/冷启动重建交错多计）
        String preInsertEpoch = reservationCountStore.captureEpochBeforeInsert(showId);

        // 5. 落库（DB uk_user_show 物理拦截并发重复提交，并锚定当前开售配置版本号）
        long reservationId = idGenerator.nextId();
        int saleConfigVersion = show.effectiveSaleConfigVersion();
        ReservationDO record = ReservationDO.builder()
                .id(reservationId)
                .userId(userId)
                .showId(showId)
                .remindStatus(ReservationDO.REMIND_PENDING)
                .remindConfigVersion(saleConfigVersion)
                .nextRetryTime(LocalDateTime.now())
                .deleted(0)
                .build();
        try {
            reservationMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.RESERVATION_DUPLICATE);
        }

        // 6. 落库成功后写入布隆并更新实时蓄水计数（CAS-INCR 或两阶段 Fence 重建）
        if (bloom != null) {
            try {
                bloom.add(userId);
            } catch (Exception ex) {
                log.warn("Bloom filter add failed for showId={}, userId={}", showId, userId, ex);
            }
        }
        long currentCount = reservationCountStore.incrementAfterDbInsert(showId, preInsertEpoch);

        // 7. 事务外投递 RocketMQ 5.x 开票前延迟提醒（Broker 确认 CAS->3，异常 CAS->2 等待定时补偿，已开售 CAS->4）
        boolean remindScheduled = showRemindProducer.scheduleRemind(reservationId, userId, show);

        log.info("Reservation created: reservationId={}, userId={}, showId={}, version={}, count={}, remindScheduled={}",
                reservationId, userId, showId, saleConfigVersion, currentCount, remindScheduled);
        return ReservationVO.builder()
                .reservationId(reservationId)
                .showId(showId)
                .currentCount(currentCount)
                .remindScheduled(remindScheduled)
                .saleConfigVersion(saleConfigVersion)
                .build();
    }

    /**
     * 查询本人某场次预约状态、实名前置资格、官方最新开售时间/版本与服务端时钟（解决超时/换设备恢复与开票前实名引导）
     */
    public MyReservationVO getMyReservation(long userId, long showId) {
        ShowBasicDTO show = showReadFacade.getShowBasic(showId);
        if (show == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }
        ReservationDO record = reservationMapper.selectByUserAndShow(userId, showId);
        boolean verified = userFacade.isRealNameVerified(userId);
        return buildMyReservationVO(show, record, verified, LocalDateTime.now());
    }

    /**
     * 查询本人预约列表（我的预约统一入口：展示全部预约场次的最新开售时间、版本与下一步，按开售时间由近到远排序）
     */
    public List<MyReservationVO> listMyReservations(long userId, Integer limit) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        List<ReservationDO> records = reservationMapper.selectByUserId(userId, safeLimit);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        boolean verified = userFacade.isRealNameVerified(userId);
        LocalDateTime now = LocalDateTime.now();
        Map<Long, ShowBasicDTO> showCache = new HashMap<>();
        List<MyReservationVO> result = new ArrayList<>(records.size());
        for (ReservationDO record : records) {
            ShowBasicDTO show = showCache.computeIfAbsent(record.getShowId(), showReadFacade::getShowBasic);
            if (show == null) {
                continue;
            }
            result.add(buildMyReservationVO(show, record, verified, now));
        }
        result.sort(Comparator
                .comparing((MyReservationVO v) -> "SALE_ENDED".equals(v.getNextAction()))
                .thenComparing(MyReservationVO::getSaleStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MyReservationVO::getShowId, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private MyReservationVO buildMyReservationVO(ShowBasicDTO show, ReservationDO record, boolean verified, LocalDateTime now) {
        boolean ended;
        boolean entryOpen;
        String entryState = show.getEntryState();
        if (entryState != null && !entryState.isBlank()) {
            ended = "ENDED".equalsIgnoreCase(entryState);
            entryOpen = "OPEN".equalsIgnoreCase(entryState);
        } else {
            ended = SHOW_STATUS_ENDED.equalsIgnoreCase(show.getSaleStatus())
                    || (show.getSaleEndTime() != null && now.isAfter(show.getSaleEndTime()));
            entryOpen = !ended
                    && show.getSaleStartTime() != null
                    && !now.isBefore(show.getSaleStartTime());
        }

        boolean reserved = record != null;
        String nextAction;
        if (ended) {
            nextAction = "SALE_ENDED";
        } else if (!verified) {
            nextAction = "VERIFY_IDENTITY";
        } else if (entryOpen) {
            nextAction = "ENTER_SECKILL";
        } else if (reserved) {
            nextAction = "WAIT_FOR_SALE";
        } else {
            nextAction = "RESERVE";
        }

        Integer remindStatus = reserved ? record.getRemindStatus() : null;
        return MyReservationVO.builder()
                .reserved(reserved)
                .realNameVerified(verified)
                .reservationId(reserved ? record.getId() : null)
                .showId(show.getShowId())
                .showTitle(show.getTitle())
                .saleStartTime(show.getSaleStartTime())
                .saleEndTime(show.getSaleEndTime())
                .serverTime(now)
                .saleStatus(show.getSaleStatus())
                .saleConfigVersion(show.effectiveSaleConfigVersion())
                .remindStatus(remindStatus)
                .remindStatusLabel(ReservationDO.remindStatusLabel(remindStatus))
                .entryOpen(entryOpen)
                .nextAction(nextAction)
                .build();
    }

    public ReservationCountVO getReservationCount(long showId) {
        ReservationCountStore.CountSnapshot snapshot = reservationCountStore.getCountSnapshot(showId);
        return ReservationCountVO.builder()
                .showId(showId)
                .count(snapshot.count())
                .asOfEpochMs(snapshot.asOfEpochMs())
                .stale(snapshot.stale())
                .countAvailable(snapshot.countAvailable())
                .build();
    }

    private RBloomFilter<Long> getOrInitBloomFilter(long showId, LocalDateTime saleEndTime) {
        try {
            RBloomFilter<Long> bloom = redissonClient.getBloomFilter(RedisKeys.reservationBloom(showId));
            if (bloom.tryInit(properties.getBloomExpectedInsertions(), properties.getBloomFalseProbability())) {
                Instant expireAt = resolveBloomExpireAt(saleEndTime);
                bloom.expire(expireAt);
            }
            return bloom;
        } catch (Exception ex) {
            log.warn("Bloom filter init failed for showId={}, degrading to DB unique index only", showId, ex);
            return null;
        }
    }

    private Instant resolveBloomExpireAt(LocalDateTime saleEndTime) {
        Instant now = Instant.now();
        if (saleEndTime != null) {
            Instant candidate = saleEndTime.plusDays(1).atZone(ZoneId.systemDefault()).toInstant();
            if (candidate.isAfter(now)) {
                return candidate;
            }
        }
        return now.plus(DEFAULT_BLOOM_TTL);
    }

    private boolean existsInDb(long userId, long showId) {
        Long count = reservationMapper.selectCount(
                new LambdaQueryWrapper<ReservationDO>()
                        .eq(ReservationDO::getUserId, userId)
                        .eq(ReservationDO::getShowId, showId)
        );
        return count != null && count > 0;
    }
}
