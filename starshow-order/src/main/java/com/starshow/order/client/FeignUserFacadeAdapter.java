package com.starshow.order.client;

import com.starshow.api.facade.UserFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 分布式模式下的 UserFacade OpenFeign 适配器（严格限定 @Profile("distributed")，monolith 下零装配）
 */
@Slf4j
@Component("orderFeignUserFacadeAdapter")
@Profile("distributed")
@RequiredArgsConstructor
public class FeignUserFacadeAdapter implements UserFacade {

    private final OrderUserFeignClient userFeignClient;

    @Override
    public boolean isRealNameVerified(long userId) {
        try {
            Result<Boolean> resp = userFeignClient.isRealNameVerified(userId);
            if (resp == null || !resp.isSuccess()) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "用户实名状态校验失败");
            }
            return Boolean.TRUE.equals(resp.getData());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Feign call to starshow-user /internal/users/{}/verified failed", userId, ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "用户服务暂不可用，请稍后重试", ex);
        }
    }
}
