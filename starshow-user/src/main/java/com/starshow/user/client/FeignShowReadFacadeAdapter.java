package com.starshow.user.client;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 分布式模式 ShowReadFacade OpenFeign 代理适配（严格限定 @Profile("distributed")，
 * 单进程 monolith 模式下直接注入 starshow-show 本地 Bean，零 HTTP 自调用）。
 */
@Component
@Profile("distributed")
@RequiredArgsConstructor
public class FeignShowReadFacadeAdapter implements ShowReadFacade {

    private final ShowServiceFeignClient showServiceFeignClient;

    @Override
    public ShowBasicDTO getShowBasic(long showId) {
        Result<ShowBasicDTO> response = showServiceFeignClient.getShowBasic(showId);
        return response == null ? null : response.getData();
    }

    @Override
    public TierSnapshotDTO getTierSnapshot(long showId, long tierId) {
        throw new UnsupportedOperationException("getTierSnapshot remote endpoint will be introduced in Phase 2");
    }
}
