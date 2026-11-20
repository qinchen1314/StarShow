package com.starshow.order.client;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.dto.ZoneSeatSpecDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 分布式模式下的 ShowReadFacade OpenFeign 适配器（严格限定 @Profile("distributed")，monolith 下零装配）
 */
@Slf4j
@Component("orderFeignShowReadFacadeAdapter")
@Profile("distributed")
@RequiredArgsConstructor
public class FeignShowReadFacadeAdapter implements ShowReadFacade {

    private final OrderShowFeignClient showFeignClient;

    @Override
    public ShowBasicDTO getShowBasic(long showId) {
        try {
            Result<ShowBasicDTO> resp = showFeignClient.getShowBasic(showId);
            if (resp == null) {
                return null;
            }
            if (!resp.isSuccess()) {
                if (ErrorCode.SHOW_NOT_FOUND.getCode().equals(resp.getCode())) {
                    return null;
                }
                throw new BizException(ErrorCode.SYSTEM_ERROR, resp.getMessage());
            }
            return resp.getData();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Feign call to starshow-show /internal/shows/{}/basic failed", showId, ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "演出服务暂不可用，请稍后重试", ex);
        }
    }

    @Override
    public TierSnapshotDTO getTierSnapshot(long showId, long tierId) {
        try {
            Result<TierSnapshotDTO> resp = showFeignClient.getTierSnapshot(showId, tierId);
            if (resp == null) {
                return null;
            }
            if (!resp.isSuccess()) {
                if (ErrorCode.TIER_NOT_FOUND.getCode().equals(resp.getCode())
                        || ErrorCode.SHOW_NOT_FOUND.getCode().equals(resp.getCode())) {
                    return null;
                }
                throw new BizException(ErrorCode.SYSTEM_ERROR, resp.getMessage());
            }
            return resp.getData();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Feign call to starshow-show /internal/shows/{}/tiers/{}/snapshot failed", showId, tierId, ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "演出票档服务暂不可用，请稍后重试", ex);
        }
    }

    @Override
    public ZoneSeatSpecDTO getZoneSeatSpec(long showId, String zoneId) {
        try {
            Result<ZoneSeatSpecDTO> resp = showFeignClient.getZoneSeatSpec(showId, zoneId);
            if (resp == null) {
                return null;
            }
            if (!resp.isSuccess()) {
                if (ErrorCode.SHOW_NOT_FOUND.getCode().equals(resp.getCode())) {
                    return null;
                }
                throw new BizException(ErrorCode.SYSTEM_ERROR, resp.getMessage());
            }
            return resp.getData();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Feign call to starshow-show /internal/shows/{}/zones/{}/seats/spec failed", showId, zoneId, ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "演出座位配置服务暂不可用，请稍后重试", ex);
        }
    }
}
