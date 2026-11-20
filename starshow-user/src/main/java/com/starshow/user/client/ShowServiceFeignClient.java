package com.starshow.user.client;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 演出服务内部只读接口 OpenFeign 客户端（仅在 distributed profile 下随 DistributedFeignEnableConfig 激活）
 */
@FeignClient(
        name = "starshow-show",
        url = "${starshow.services.show-uri:${SHOW_SERVICE_URI:http://127.0.0.1:8002}}",
        configuration = ShowServiceFeignClientConfig.class
)
public interface ShowServiceFeignClient {

    @GetMapping("/internal/shows/{showId}/basic")
    Result<ShowBasicDTO> getShowBasic(@PathVariable("showId") long showId);
}
