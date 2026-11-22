package com.starshow.show.client;

import com.starshow.api.facade.UserFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("distributed")
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class FeignWaitingRoomUserFacade implements UserFacade {
    private final UserServiceFeignClient client;

    @Override
    public boolean isRealNameVerified(long userId) {
        Result<Boolean> result = client.isRealNameVerified(userId);
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw BizException.serviceUnavailable(ErrorCode.QUEUE_UNAVAILABLE, 2000, "实名状态暂不可确认，请稍后重试");
        }
        return result.getData();
    }
}
