# StarShow（星演票务）系统全景架构与工程契约

> 本文档为 `starshow-ticket` 仓库的核心架构设计说明，聚焦高并发削峰、库存强一致、五层纵深幂等与 V1→V4 真实演进落地。

## 1. 微服务模块边界与双形态运行

| 模块 | 核心职责 | 归属表 |
| --- | --- | --- |
| `starshow-common` | 统一返回 `Result<T>`、错误码、基因法 Snowflake、订单状态机、AES-GCM/HMAC-SHA256 加密、Redis Key 契约、Long→String 序列化 | 无状态 |
| `starshow-api` | `UserFacade` / `ShowReadFacade` 内部接口、DTO、RocketMQ Event Schema | 无状态 |
| `starshow-gateway` | 统一路由、JWT 黑名单鉴权、全局限流 | Redis 只读 |
| `starshow-user` | 链路一：实名防黄牛（一证一账号）、布隆过滤器预约蓄水、开票前延迟提醒 | `t_user`, `t_user_identity`, `t_reservation` |
| `starshow-show` | 链路二：L1(Caffeine)+L2(Redis 逻辑过期) 多级缓存、分区 Bitmap 座位图、开票预热 | `t_show`, `t_ticket_tier`, `t_seat` |
| `starshow-order` | 链路三+四：V1→V4 策略抢票引擎、Lua 原子预扣/选座/回补、事务消息、基因分表、15min 关单、出票履约 | `t_order_0..3`, `t_ticket_0..3`, `t_mq_consume_log` |
| `starshow-payment` | 链路四：支付流水、Mock 支付故障注入沙箱、退款 Saga 编排、四方对账 | `t_payment_order`, `t_refund_order`, `t_reconciliation_diff` |
| `starshow-boot-all` | 本地极速开发单进程聚合启动器（通过 Facade 接口本地直调，零自调用死锁） | 聚合运行 |

## 2. 六项核心工程决策

1. **冷热数据分治**：演出元数据走 MySQL → Caffeine → Redis 逻辑过期；开票窗口期实时库存与座位图以 Redis 为写入准事实源，严禁被 DB 回写覆盖，由对账任务收敛。
2. **Redis Cluster 零 `CROSSSLOT`**：所有参与同一段 Lua 脚本的 Key（库存、已购、回查凭证、悬挂 ZSET、回补墓碑、座位 Bitmap）严格共享 `{showId}` Hash Tag。
3. **RocketMQ 事务消息 + 防悬挂闭环**：
   - 预扣 Lua 原子写入 `deduct_log` 与 `deduct_pending`（ZSET 时间索引）；
   - Broker 回查不存在时先写 `rollback_done` 防悬挂墓碑再返回 `ROLLBACK`，阻止延迟到达的 Lua 造成孤立扣减；
   - 悬挂对账通过 `ZRANGEBYSCORE` 以 `O(log N + M)` 拉取超时未落库单，严禁全库 `SCAN`。
4. **基因法 Snowflake + 防前端精度丢失**：
   - 位宽：`1bit 符号 | 41bit 时间戳 | 6bit 机器ID | 12bit 序列号 | 4bit userId 基因位`；
   - Jackson 全局将 `Long` 序列化为 `String`，保护 JS 端不丢尾部基因位。
5. **同库本地事务与行锁保护**：
   - `t_order_0..3` 使用 `UK(user_id, show_id, active_flag)`（关单/退款后 `active_flag = order_no`，允许合法重抢）；
   - `t_mq_consume_log` 携带 `user_id`（单库同事务；分库时配置为 Binding Table）；
   - 消费端通过匀速限流 / 批量合并扣减保护 `t_ticket_tier` 热点行锁。
6. **支付/关单竞速底线与隐私防护**：
   - Mock 支付单默认 `14m30s` 过期，业务超时关单 `15m`（错峰 30s），同时内置故障注入演练极端竞速；
   - `CLOSED` 为绝对终态，滞后支付自动转异常原路退款；
   - 身份证唯一校验采用 `HMAC-SHA256(idCard, serverPepper)` 抵御低熵彩虹表碰撞，原文使用 `AES-256-GCM` 随机 IV 加密落库。
