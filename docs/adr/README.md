# 架构决策记录（ADR）总账本

本目录记录 StarShow 所有关键架构权衡与已拍板红线。新增或修订规则时，按 `ADR-###-slug.md` 格式落盘并同步更新下表与 `.agents/skills/starshow/references/adr.md`。

| 编号 | 状态 | 决策主题 | 归属文档 / 代码锚点 |
| --- | --- | --- | --- |
| ADR-001 | 已采纳 | 1 网关 + 4 业务微服务收敛边界 + `starshow-boot-all` 双形态运行（Facade 防自调用死锁） | `docs/ARCHITECTURE.md`、`starshow-api` |
| ADR-002 | 已采纳 | 冷热数据分治：静态元数据走 L1+L2 逻辑过期，售卖期实时库存以 Redis 为准事实源禁被 DB 覆盖 | `docs/domains/02-show-inventory.md` |
| ADR-003 | 已采纳 | V4 抢票采用 RocketMQ 事务消息 + Lua 回查凭证 + `rollback_done` 防悬挂墓碑 + `ZSET` 悬挂索引 | `docs/domains/03-order-seckill.md`、`seckill_deduct.lua` |
| ADR-004 | 已采纳 | 同场次 Lua 脚本全部 Key 共享 `{showId}` Hash Tag，杜绝 Redis Cluster `CROSSSLOT` | `RedisKeys.java` |
| ADR-005 | 已采纳 | 基因法 Snowflake 严格位宽 `1+41+6+12+4` + 全局 `Long -> String` 序列化防前端丢基因位 | `GeneSnowflakeIdGenerator.java`、`JacksonLongToStringConfig.java` |
| ADR-006 | 已采纳 | `t_order` 使用 `UK(user_id, show_id, active_flag)` 支持合法重抢；`t_mq_consume_log` 绑定同库本地事务 | `docker/mysql/init/01_schema.sql` |
| ADR-007 | 已采纳 | 同时支持自动配座与分区 Bitmap 连座无锁原子预扣/回补 | `seckill_seat_deduct.lua`、`seckill_rollback.lua` |
| ADR-008 | 已采纳 | 支付有效期(`14m30s`)与关单(`15m`)错峰 + 关单前主动查单 + `CLOSED` 永不复活滞后转退款 | `OrderStateMachine.java`、`docs/domains/04-payment-reconcile.md` |
| ADR-009 | 已采纳 | 身份证 `AES-256-GCM` 密文 + `HMAC-SHA256(pepper)` 唯一哈希防彩虹表 + 开源全仓脱敏红线 | `CryptoUtil.java`、`.env.example` |
