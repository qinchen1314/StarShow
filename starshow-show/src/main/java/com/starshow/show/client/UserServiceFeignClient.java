package com.starshow.show.client;

import com.starshow.api.dto.ShowRescheduleSyncDTO;
import com.starshow.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 用户/预约服务改期联动 OpenFeign 客户端（仅在 distributed profile 下激活）
 */
@FeignClient(
        name = "starshow-user",
        url = "${starshow.services.user-uri:${USER_SERVICE_URI:http://127.0.0.1:8001}}",
        configuration = UserServiceFeignClientConfig.class
)
public interface UserServiceFeignClient {

    @PostMapping("/internal/reservations/shows/{showId}/reschedule")
    Result<ShowRescheduleSyncDTO> rescheduleShowReminders(@PathVariable("showId") long showId);

    @GetMapping("/internal/users/{userId}/verified")
    Result<Boolean> isRealNameVerified(@PathVariable("userId") long userId);
}
