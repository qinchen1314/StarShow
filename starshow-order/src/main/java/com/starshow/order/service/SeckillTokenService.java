package com.starshow.order.service;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.dto.SeckillTokenRequest;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.vo.SeckillTokenVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 链路三（节点 0）：短期抢票凭证签发服务（POST /api/v1/seckill/token）
 * - 强校验实名资格（A0104）、场次存在性（A0201）、配置版本（A0205）与售卖窗口/门禁（A0202 / B0201）；
 * - 凭证绑定 (userId, showId, saleConfigVersion)，不绑死单一 tierId，支持售罄/冲突后有效期内切换票档重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserFacade userFacade;
    private final ShowReadFacade showReadFacade;
    private final OrderRedisScripts redisScripts;
    private final SeckillProperties properties;

    public SeckillTokenVO issueToken(long userId, SeckillTokenRequest request) {
        if (!userFacade.isRealNameVerified(userId)) {
            throw new BizException(ErrorCode.IDENTITY_REQUIRED);
        }
        long showId = request.getShowId();
        ShowBasicDTO show = showReadFacade.getShowBasic(showId);
        if (show == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }

        int currentVersion = show.effectiveSaleConfigVersion();
        if (request.getSaleConfigVersion() != null && request.getSaleConfigVersion() != currentVersion) {
            throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED,
                    "开售配置版本已变更，当前权威版本=" + currentVersion);
        }

        validateSaleWindowAndGate(show, currentVersion);

        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        long ttlSec = Math.max(10L, properties.getTokenTtlSeconds());
        redisScripts.saveSeckillToken(userId, showId, token, currentVersion, ttlSec);

        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        return SeckillTokenVO.builder()
                .seckillToken(token)
                .showId(showId)
                .saleConfigVersion(currentVersion)
                .expireAt(OffsetDateTime.ofInstant(now.plusSeconds(ttlSec), zone))
                .serverTime(OffsetDateTime.ofInstant(now, zone))
                .build();
    }

    private void validateSaleWindowAndGate(ShowBasicDTO show, int currentVersion) {
        long nowMs = System.currentTimeMillis();
        long saleStartMs = show.getSaleStartTime() == null ? 0L
                : show.getSaleStartTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long saleEndMs = show.getSaleEndTime() == null ? 0L
                : show.getSaleEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String saleStatus = show.getSaleStatus() == null ? "INIT" : show.getSaleStatus().toUpperCase();

        if ("ENDED".equals(saleStatus) || (saleEndMs > 0L && nowMs >= saleEndMs)) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "本场演出已停止售票");
        }
        if (saleStartMs > 0L && nowMs < saleStartMs) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "本场演出尚未开票");
        }

        if ("V4".equals(properties.normalizedStrategy())) {
            OrderRedisScripts.ReadyGateSnapshot gate = redisScripts.readReadyGate(show.getShowId());
            if (gate == null) {
                if ("ON_SALE".equals(saleStatus) || "SOLD_OUT".equals(saleStatus)) {
                    throw BizException.serviceUnavailable(ErrorCode.STOCK_UNAVAILABLE, 1000L, "库存热态门禁暂不可用，请稍后刷新");
                }
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "演出尚未完成预热，暂不可抢票");
            }
            if (!"READY".equals(gate.getStage()) && !"OPEN".equals(gate.getStage())) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "演出当前状态不可抢票: " + gate.getStage());
            }
            if (gate.getVersion() == null || gate.getVersion() != currentVersion) {
                throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, "开售配置版本正在同步，请刷新重试");
            }
            if ((gate.getSaleStartEpochMs() != null && nowMs < gate.getSaleStartEpochMs())
                    || (gate.getSaleEndEpochMs() != null && nowMs >= gate.getSaleEndEpochMs())) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "当前不在演出售卖时间窗口内");
            }
        } else {
            if (!"ON_SALE".equals(saleStatus) && !"PREHEATED".equals(saleStatus) && !"SOLD_OUT".equals(saleStatus)) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "演出当前状态不可抢票: " + saleStatus);
            }
        }
    }
}
