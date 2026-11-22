package com.starshow.show.queue;

import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.util.ShowTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
public class WaitingRoomScheduleProjection {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WaitingRoomStore store;
    private final WaitingRoomProperties properties;
    private final ShowMapper shows;
    private final RedissonClient showRedis;

    public boolean includes(long showId) {
        return properties.includes(showId);
    }

    public void pause(ShowDO current) {
        var snapshot = snapshot(current.getSaleConfigVersion(), current.getSaleStartTime(), current.getSaleEndTime());
        String state = store.configure("PAUSE", current.getId(), snapshot, UUID.randomUUID().toString());
        if ("STARTED".equals(state)) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "已到原开售时刻，不能改期");
        }
        if (!"OK".equals(state)) {
            throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED);
        }
    }

    public void publishCommitted(long showId, int version, LocalDateTime start, LocalDateTime end) {
        if (includes(showId)) {
            store.configure("PUBLISH", showId, snapshot(version, start, end), "");
        }
    }

    @Scheduled(fixedDelayString = "${starshow.waiting-room.maintenance-ms:2000}")
    public void maintain() {
        for (long showId : properties.getShowIds()) {
            try {
                var current = store.projection(showId);
                if (current == null || !current.fence().isEmpty()) {
                    repairWithShowLock(showId);
                } else if (System.currentTimeMillis() >= current.startAt() && System.currentTimeMillis() < current.endAt()) {
                    store.inspect(WaitingRoomStore.Action.SEAL, showId, current.version(), 0, "");
                }
            } catch (RuntimeException ex) {
                log.debug("Waiting room schedule maintenance deferred for showId={} ({})", showId, ex.getClass().getSimpleName());
            }
        }
    }

    private void repairWithShowLock(long showId) {
        RLock lock = showRedis.getLock(RedisKeys.showOpLock(showId));
        if (!lock.tryLock()) {
            return;
        }
        try {
            var current = store.projection(showId);
            if (current != null && current.fence().isEmpty()) {
                return;
            }
            ShowDO authoritative = shows.selectById(showId);
            if (authoritative != null) {
                var next = snapshot(authoritative.getSaleConfigVersion(), authoritative.getSaleStartTime(), authoritative.getSaleEndTime());
                if (next.endAt() + 86_400_000L > System.currentTimeMillis()) {
                    store.configure("PUBLISH", showId, next, current == null ? "" : current.fence());
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private static WaitingRoomStore.Projection snapshot(Integer version, LocalDateTime start, LocalDateTime end) {
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        return new WaitingRoomStore.Projection(version == null ? 1 : version, ShowTimeUtil.toEpochMs(start),
                ShowTimeUtil.toEpochMs(end), HexFormat.of().formatHex(seed), "");
    }
}
