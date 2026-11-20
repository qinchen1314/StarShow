# 高并发 × 一致性 × 幂等性工程细则

用于链路二（缓存/座位图）、链路三（抢票 V1~V4）、链路四（支付/关单/退款/对账）开发。

## 1. 读路径高并发（链路二）

- **L1（Caffeine）**：缓存演出详情、票档元数据（TTL 60s，最大 5000 条）；热点余票允许极短缓冲（500ms~1s）保护 Redis 单分片。
- **L2（Redis）**：
  - 防穿透：Redisson `RBloomFilter` 拦截非法 `showId` + 空值短缓存（TTL 30s）。
  - 防击穿：演出详情采用**逻辑过期（Logical Expire）**——Redis 物理永不过期，包装 `expireAt` 字段；过期时仅单线程抢互斥锁异步重建，其余线程直接返回旧快照。
  - 防雪崩：常规 Key 基础 TTL + 随机抖动（0~300s）。
- **分区 Bitmap 座位图**：Key 模板 `starshow:seat:bitmap:{showId}:<zoneId>`，单区 1000~2000 座仅占 125~250 字节，杜绝 BigKey；严禁整场馆塞进同一个 Bitmap。
- **开票前预热（Preheat）**：在开票前将演出详情、票档库存、空座位 Bitmap、预约布隆过滤器预加载入 Redis 并将场次置为 `PREHEATED`。

## 2. 写路径 V4 事务消息 + 防悬挂闭环（链路三）

标准执行顺序（严禁颠倒）：

1. 入口防重：校验一次性 `seckillToken`（或网关短窗幂等）。
2. 生成基因法 Snowflake `orderNo`（携带 `userId & 0x0F`）。
3. 发送 RocketMQ **Half（半）消息**到 `TOPIC_ORDER_CREATE`。
4. 在 `executeLocalTransaction` 中执行原子 Lua 脚本：
   - 自动配座：`starshow-order/src/main/resources/lua/seckill_deduct.lua`
   - 自主选座（支持连座）：`starshow-order/src/main/resources/lua/seckill_seat_deduct.lua`
   - Lua 返回码：`1` 成功（`COMMIT`）、`-1` 库存不足（`ROLLBACK`）、`-2` 重复抢购（`ROLLBACK`）、`-3` 命中防悬挂墓碑（`ROLLBACK`）、`-4` 座位冲突（`ROLLBACK`）。
5. **Broker 事务回查（`checkLocalTransaction`）**：
   - 查询 `EXISTS starshow:deduct_log:{showId}:<orderNo>`；
   - 存在 → 返回 `COMMIT`；
   - **不存在 → 先写防悬挂墓碑** `SET starshow:rollback_done:{showId}:<orderNo> 1 NX EX 86400`，再返回 `ROLLBACK`！防止网络延迟到达的 Lua 造成孤立扣减。
6. **消费端三层幂等落单 + 行锁保护**：
   - 本地事务内执行：`INSERT t_mq_consume_log`（主键幂等）+ `INSERT t_order_{shard}`（`UK(user_id, show_id, active_flag)` 兜底）+ 扣减 `t_ticket_tier.available_stock`（`WHERE available_stock >= qty` 物理防超卖底线）；
   - **InnoDB 热点行锁保护**：消费端必须限制并发消费线程数或采用批量聚合扣减，避免 32+ 线程并发争抢 `t_ticket_tier` 同一行锁拖垮 DB；
   - 落库提交后：清理悬挂索引 `ZREM starshow:deduct_pending:{showId} <orderNo>`，并投递 15 分钟延迟关单消息至 `TOPIC_ORDER_TIMEOUT`。
7. **响应体验**：Half 消息本地事务提交后即刻返回 HTTP `202`（`code=00202`, `orderNo`），前端轮询/SSE 查询订单落库状态。

## 3. 五层纵深幂等防线

| 层级 | 手段 | 失效兜底 |
| --- | --- | --- |
| L1 入口 | 一次性 `seckillToken` / 网关防重放 | L2 拦截 |
| L2 缓存原子 | Lua `SISMEMBER starshow:bought:{showId} userId` | L4 拦截 |
| L3 MQ 消费 | `t_mq_consume_log`（主键 `msg_key`，与订单同事务） | L4 拦截 |
| L4 DB 唯一索引 | `uk_user_show_active`、`uk_id_card_hmac`、`uk_channel_trade_no` | 物理拒绝 |
| L5 状态机 CAS | `UPDATE ... SET status = :target WHERE order_no = :no AND status = :expected` + `seckill_rollback.lua` `SETNX rollback_done` | 影响行数=0 直接幂等返回 |

## 4. 支付回调 vs 15 分钟超时关单竞速（链路四）

- **错峰设计**：Mock 支付单默认过期时间 `14m30s`，超时关单延迟消息 `15m`（错峰 30 秒降低常态撞车率）；保留 `?fault=RACE_WITH_CLOSE` 用于演练极端竞速。
- **关单前强制主动查单**：15 分钟延迟消息到达时，先拿订单级 Redisson 锁 `starshow:lock:order:<orderNo>`，再主动查支付渠道状态；若渠道已付，转入补单出票流程，绝不误关。
- **终态铁律（防复活）**：一旦订单 CAS 变为 `CLOSED`（同时将 `active_flag` 置为 `order_no` 并执行 `seckill_rollback.lua` 回补库存/释放座位），**严禁再将订单改为 `PAID`**！若此时滞后支付回调到达，标记支付单为 `ABNORMAL_PAID`，自动创建退款单走原路退款 Saga。
- **退款 Saga 状态机**：`INIT → LOCK_TICKET → REFUND_PAY → ROLLBACK_STOCK → DONE`，每步持久化状态，失败由定时任务按步幂等续跑。
- **双重对账底网**：
  - 每 5 分钟准实时巡检：① `ZRANGEBYSCORE starshow:deduct_pending:{showId}` 捞取超时 5min 悬挂扣减并幂等回补；② 扫描超 16min 仍 `WAIT_PAY` 的漏网订单执行查单+关单；
  - T+1 财务全量对账：比对「渠道流水 vs `t_payment_order` vs `t_order` vs `t_ticket`」，差异落入 `t_reconciliation_diff`。
