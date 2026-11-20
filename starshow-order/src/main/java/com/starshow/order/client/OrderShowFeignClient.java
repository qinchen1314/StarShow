package com.starshow.order.client;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.dto.ZoneSeatSpecDTO;
import com.starshow.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 分布式模式下 starshow-order 调用 starshow-show 的内部 OpenFeign 契约
 */
@FeignClient(
        name = "order-starshow-show",
        url = "${starshow.services.show-uri:http://127.0.0.1:8002}",
        configuration = OrderInternalFeignClientConfig.class
)
public interface OrderShowFeignClient {

    @GetMapping("/internal/shows/{showId}/basic")
    Result<ShowBasicDTO> getShowBasic(@PathVariable("showId") long showId);

    @GetMapping("/internal/shows/{showId}/tiers/{tierId}/snapshot")
    Result<TierSnapshotDTO> getTierSnapshot(@PathVariable("showId") long showId, @PathVariable("tierId") long tierId);

    @GetMapping("/internal/shows/{showId}/zones/{zoneId}/seats/spec")
    Result<ZoneSeatSpecDTO> getZoneSeatSpec(@PathVariable("showId") long showId, @PathVariable("zoneId") String zoneId);
}
