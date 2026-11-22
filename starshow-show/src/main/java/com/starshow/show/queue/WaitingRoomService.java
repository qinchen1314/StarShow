package com.starshow.show.queue;

import com.starshow.api.facade.UserFacade;
import com.starshow.common.crypto.HmacSigner;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.util.ShowTimeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
class WaitingRoomService {
    private final WaitingRoomStore store;
    private final ObjectProvider<UserFacade> users;
    private final QueueReadToken tokens;

    View join(long showId, long userId, Integer displayedVersion) {
        WaitingRoomStore.Projection snapshot = projection(showId);
        int requestedVersion = snapshot.version();
        if (displayedVersion != null && displayedVersion != requestedVersion) {
            throw versionChanged(requestedVersion, ShowTimeUtil.toOffsetDateTime(snapshot.startAt()));
        }
        var outcome = store.inspect(WaitingRoomStore.Action.START, showId, requestedVersion, userId, "");
        if (outcome.status() == WaitingRoomStore.Status.NEEDS_IDENTITY) {
            verifyIdentity(userId);
            String id = UUID.randomUUID().toString();
            String shuffle = HmacSigner.sign(snapshot.seed(), "queue-pre:", id.getBytes(StandardCharsets.UTF_8));
            outcome = store.join(showId, requestedVersion, userId, id, "account:" + userId, shuffle);
        }
        return toView(showId, userId, outcome, true);
    }

    View read(long showId, Long signedInUser, String readToken) {
        QueueReadToken.Bound bound = readToken == null ? null : tokens.read(readToken, showId, signedInUser);
        if (bound == null && signedInUser == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        var snapshot = projection(showId);
        long userId = bound == null ? signedInUser : bound.userId();
        int version = bound == null ? snapshot.version() : bound.version();
        var outcome = store.inspect(WaitingRoomStore.Action.READ, showId, version, userId,
                bound == null ? "" : bound.queueId());
        return toView(showId, userId, outcome, false);
    }

    private WaitingRoomStore.Projection projection(long showId) {
        if (!store.includes(showId)) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND, "该场次未启用候场");
        }
        var snapshot = store.projection(showId);
        if (snapshot == null) {
            throw WaitingRoomStore.unavailable();
        }
        return snapshot;
    }

    private void verifyIdentity(long userId) {
        UserFacade facade = users.getIfAvailable();
        if (facade == null) {
            throw WaitingRoomStore.unavailable();
        }
        boolean verified;
        try {
            verified = facade.isRealNameVerified(userId);
        } catch (RuntimeException ex) {
            throw WaitingRoomStore.unavailable();
        }
        if (!verified) {
            throw BizException.withData(ErrorCode.IDENTITY_REQUIRED, "完成实名认证后即可加入候场",
                    Map.of("nextAction", "VERIFY_IDENTITY"));
        }
    }

    private View toView(long showId, long userId, WaitingRoomStore.Result result, boolean issueToken) {
        int version = (int) result.version();
        var serverTime = ShowTimeUtil.toOffsetDateTime(result.now());
        var saleStart = ShowTimeUtil.toOffsetDateTime(result.startAt());
        return switch (result.status()) {
            case WAITING -> {
                var entry = result.entry();
                String token = issueToken ? tokens.issue(showId, userId, version, entry.queueId(), result.now(), result.endAt()) : null;
                yield new View("WAITING", version, saleStart, serverTime, ThreadLocalRandom.current().nextInt(20000, 30001),
                        "WAIT", entry.queueId(), ShowTimeUtil.toOffsetDateTime(result.endAt()), token);
            }
            case EXPIRED -> new View("EXPIRED", version, saleStart, serverTime, 0, "REJOIN_QUEUE", null, null, null);
            case ENDED -> new View("ENDED", version, saleStart, serverTime, 0, "SALE_ENDED", null, null, null);
            case VERSION_CHANGED -> {
                if (issueToken) {
                    throw versionChanged(version, saleStart);
                }
                yield new View("EXPIRED", version, saleStart, serverTime, 0, "REJOIN_QUEUE", null, null, null);
            }
            case RATE_LIMITED -> throw new BizException(ErrorCode.TOO_MANY_REQUESTS, 429, result.retryMs(),
                    "候场操作过于频繁，请稍后重试");
            case IDENTITY_CONFLICT -> throw new BizException(ErrorCode.IDENTITY_DUPLICATE, "该身份已有候场记录");
            case UNAVAILABLE, NEEDS_IDENTITY -> throw WaitingRoomStore.unavailable();
        };
    }

    private static BizException versionChanged(int version, OffsetDateTime saleStart) {
        return BizException.withData(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, "开售时间已更新，请重新加入候场",
                Map.of("saleConfigVersion", version, "saleStartTime", saleStart, "nextAction", "REJOIN_QUEUE"));
    }

    record View(String queueStatus, int saleConfigVersion, OffsetDateTime saleStartTime, OffsetDateTime serverTime,
                int retryAfterMs, String nextAction, String queueId, OffsetDateTime expiresAt, String queueReadToken) {}
}
