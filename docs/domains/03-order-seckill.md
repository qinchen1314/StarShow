# 板块 03：V1→V4 抢票下单引擎与电子票履约（链路三 + 四）

- **映射代码模块**：`starshow-order`
- **计划阶段**：Phase 3（`v0.4.0-chain3`）+ Phase 4 关单出票联动
- **状态**：⬜ 未开始（Lua 原子契约脚本已于 Phase 0 就绪）

## 1. 数据与中间件归属
- **MySQL 表**：`t_order_0..3`、`t_ticket_0..3`、`t_mq_consume_log`（与 `t_order` 同库本地事务）
- **Redis Key**（全部共享 `{showId}` Hash Tag）：`stock`、`bought`、`deduct_log`、`deduct_pending`（ZSET）、`rollback_done`、`seat:bitmap`、`lock:show`、`lock:tier`
- **Lua 脚本**：`seckill_deduct.lua`、`seckill_seat_deduct.lua`、`seckill_rollback.lua`
- **RocketMQ Topic**：`TOPIC_ORDER_CREATE`、`TOPIC_ORDER_TIMEOUT`、`TOPIC_TICKET_ISSUE`

## 2. 核心设计要点
1. **四版本策略同仓切换**：统一接口 `SeckillOrderStrategy`，按配置 `starshow.seckill.strategy` 切换：
   - `V1`：`DbOptimisticSeckillStrategy`（MySQL 乐观锁同步落单）
   - `V2`：`ShowLockSeckillStrategy`（Redisson 场次粗锁）
   - `V3`：`TierLockSeckillStrategy`（Redisson 票档细锁）
   - `V4`：`LuaMqSeckillStrategy`（Lua 无锁原子预扣/连座 + RocketMQ 事务消息 + 防悬挂墓碑 + 异步落库）
2. **基因分片与重抢防重**：订单号使用 `GeneSnowflakeIdGenerator` 嵌入 `userId & 0x0F`；单分片精准路由；`UK(user_id, show_id, active_flag)` 支持关单后合法重抢。
3. **消费端三层幂等与行锁保护**：`t_mq_consume_log` + `UK` + `t_ticket_tier` 物理底线在同一本地事务提交；控制消费并发度或批量聚合扣减保护 InnoDB 热点行锁；落库后 `ZREM deduct_pending` 并投递 15min 超时关单延迟消息。

## 3. Prove-It 验收红线（Phase 3 完成标准，L2 + L3）
- 200 并发线程抢 20 张票：V1 / V2 / V3 / V4 均达成 **0 超卖、0 重复单**，`Redis已扣 == DB有效单 == 20`；
- 选座模式 50 并发抢同一连座 `(12, 13)`：仅 1 人成功，其余返回 `SEAT_CONFLICT`；
- 模拟事务消息 Commit 丢包：Broker 回查 `deduct_log` 自动补齐落单；
- 模拟回查先 `ROLLBACK` 写墓碑：延迟到达的 Lua 返回 `-3` 拒绝扣减，零悬挂。
