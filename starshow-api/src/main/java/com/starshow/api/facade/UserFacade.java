package com.starshow.api.facade;

/**
 * 用户域内部调用契约：
 * - 单进程聚合模式 (monolith)：直接注入本地 Service Bean，零 HTTP 开销、零自调用死锁；
 * - 分布式微服务模式 (distributed)：由 OpenFeign 代理实现。
 */
public interface UserFacade {

    /**
     * 校验用户是否已完成实名认证（一证一账号防黄牛前提）
     */
    boolean isRealNameVerified(long userId);
}
