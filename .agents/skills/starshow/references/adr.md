# 架构决策记录（ADR 索引与冲突升级机制）

当某项新需求或实现方案与既有红线冲突，或需要引入新组件/新数据表/新状态流转时，**严禁静默绕开规则**，必须先对照本索引并在必要时走 ADR 升级。

## 1. 何时必须新增或修订 ADR

以下情况必须提出 ADR 并与项目负责人确认：
- 修改冷热数据事实源边界（如改动 Redis 实时库存的准事实源地位）；
- 修改订单/支付/退款状态机的状态定义或流转规则；
- 修改 `t_order` 分片键、基因位宽、或 MQ 消息事件 Schema；
- 新增核心中间件依赖或修改 V1→V4 演进策略契约；
- 任何需要豁免 `Non-Negotiables` 红线的特殊场景。

普通业务实现细节（未违背既有规则）无需新增 ADR，直接按规范实现即可。

## 2. 既有决策索引（已拍板）

| 编号 | 决策主题 | 核心拍板结论 |
| --- | --- | --- |
| ADR-001 | 服务边界收敛 | 采用 1 网关 + 4 业务微服务（库存收口至 `show`/`order`，消除跨服务 RPC 与分布式事务），叠加 `starshow-boot-all` 单进程双形态运行 |
| ADR-002 | 冷热数据分治 | 静态元数据走 MySQL → L1+L2 逻辑过期；售卖期实时库存/座位 Bitmap 以 Redis 为准事实源，禁被 DB/Canal 覆盖 |
| ADR-003 | 事务消息防悬挂闭环 | 先发 Half 消息再执行 Lua（写 `deduct_log` + `deduct_pending` ZSET）；回查缺失时先写 `rollback_done` 墓碑再 `ROLLBACK` |
| ADR-004 | Redis Cluster 零 CROSSSLOT | 同场次原子 Lua 涉及的所有 Key 统一使用 `{showId}` 单一 Hash Tag |
| ADR-005 | 基因法 Snowflake 与精度保护 | 位宽 `1+41+6+12+4`（不覆盖序列号），Jackson 全局将 `Long` 序列化为 `String` 防前端 JS 精度丢失 |
| ADR-006 | 可重抢防重索引与同库事务 | `t_order_0..3` 采用 `UK(user_id, show_id, active_flag)`；`t_mq_consume_log` 携带 `user_id` 保证与订单同本地事务 |
| ADR-007 | 选座与自动配座双模式原子化 | 通过 `seckill_deduct.lua`（配座）与 `seckill_seat_deduct.lua`（Bitmap 连座）共同实现无锁原子预扣与统一回补 |
| ADR-008 | 支付/关单竞速裁决 | 支付单 `14m30s` 与关单 `15m` 错峰；关单前主动查单；`CLOSED` 永不复活，滞后支付自动转原路退款 Saga |
| ADR-009 | 身份证隐私与开源脱敏 | 身份证 `AES-256-GCM` 密文 + `HMAC-SHA256(pepper)` 防彩虹表；全仓零硬编码密钥、零本机路径、使用 noreply 邮箱提交 |

## 3. ADR 文档模板（落盘于 `docs/adr/ADR-###-slug.md`）

```markdown
# ADR-010: <决策标题>

## 状态
提议中 / 已采纳

## 背景与冲突
<为什么既有规范无法覆盖，面临什么权衡？>

## 决策结论
<清晰、可验证、可落地的架构规则>

## 影响与代价
- 收益：<...>
- 代价与兜底：<...>
```
