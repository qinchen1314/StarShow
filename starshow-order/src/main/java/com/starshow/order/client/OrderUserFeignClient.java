package com.starshow.order.client;

import com.starshow.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 分布式模式下 starshow-order 调用 starshow-user 的内部 OpenFeign 契约
 */
@FeignClient(
        name = "order-starshow-user",
        url = "${starshow.services.user-uri:http://127.0.0.1:8001}",
        configuration = OrderInternalFeignClientConfig.class
)
public interface OrderUserFeignClient {

    @GetMapping("/internal/users/{userId}/verified")
    Result<Boolean> isRealNameVerified(@PathVariable("userId") long userId);
}
