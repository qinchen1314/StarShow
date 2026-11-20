package com.starshow.show.client;

import com.starshow.api.dto.ShowRescheduleSyncDTO;
import com.starshow.api.facade.ReservationRescheduleFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 分布式模式改期提醒重排适配器（@Profile("distributed")）：
 * 仅在独立部署 starshow-show 时装配，经 OpenFeign 调用 starshow-user 内部接口。
 */
@Slf4j
@Service
@Profile("distributed")
@RequiredArgsConstructor
public class FeignReservationRescheduleFacadeAdapter implements ReservationRescheduleFacade {

    private final UserServiceFeignClient userServiceFeignClient;

    @Override
    public ShowRescheduleSyncDTO rescheduleShowReminders(long showId) {
        Result<ShowRescheduleSyncDTO> result = userServiceFeignClient.rescheduleShowReminders(showId);
        if (result == null || !result.isSuccess()) {
            String code = result == null ? "NULL" : result.getCode();
            String msg = result == null ? "empty response" : result.getMessage();
            log.warn("Remote rescheduleShowReminders failed: showId={}, code={}, msg={}", showId, code, msg);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "联动重排预约提醒失败: " + msg);
        }
        return result.getData();
    }
}
