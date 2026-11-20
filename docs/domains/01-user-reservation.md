# 板块 01：用户鉴权、实名防黄牛与预约蓄水（链路一）

- **映射代码模块**：`starshow-gateway`、`starshow-user`
- **计划阶段**：Phase 1（`v0.2.0-chain1`）
- **状态**：🔜 待实现

## 1. 数据与中间件归属
- **MySQL 表**：`t_user`、`t_user_identity`、`t_reservation`
- **Redis Key**：`starshow:auth:blacklist:<jti>`、`starshow:rsv:bloom:<showId>`、`starshow:rsv:count:<showId>`
- **RocketMQ Topic**：`TOPIC_SHOW_REMIND`（消费组 `cg-remind-notify`）
- **对外内部契约**：实现 `com.starshow.api.facade.UserFacade`

## 2. 核心设计要点
1. **无状态 JWT + 有状态黑名单**：登录签发含唯一 `jti` 的 JWT；主动登出或风控踢人时将 `jti` 写入 Redis 黑名单（TTL 等于 Token 剩余有效期），网关每次鉴权快速放行/拦截。
2. **一证一账号防黄牛**：实名提交时计算 `HMAC-SHA256(idCard, serverPepper)` 写入 `id_card_hmac` 唯一索引，原文使用 `AES-256-GCM` 随机 IV 加密落库，对外仅返回脱敏 `id_card_masked`。
3. **预约蓄水与开票前提醒**：
   - 预约前先校验实名状态；
   - Redisson 布隆过滤器快速拦截重复预约，穿透后由 `t_reservation` 的 `UK(user_id, show_id)` 物理兜底；
   - 成功后 `INCR starshow:rsv:count:<showId>` 统计蓄水热度，并向 RocketMQ 5.x 投递开票前 10 分钟的任意时间延迟提醒消息（`setDeliverTimeMs`）。

## 3. Prove-It 验收红线（Phase 1 完成标准）
- 同一身份证号绑定第 2 个账号必须被 `uk_id_card_hmac` 拦截并返回 `A0105`；
- 同一用户重复预约同一场次必须被布隆/DB 双拦截并返回 `A0106`；
- 登出后的 JWT 再次访问受保护接口必须返回 `A0102`；
- 短延迟提醒消息（如测试设 5 秒）能准时被 `cg-remind-notify` 消费并更新 `remind_status = 1`。
