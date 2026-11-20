# 存储与中间件契约（MySQL / Redis / RocketMQ）

代码中的表名、字段名、Redis Key、MQ Topic 必须与本契约及 `docker/mysql/init/01_schema.sql` 严格一致。

## 1. MySQL 14 表与分片契约

- **通用字段规范**：主键 `BIGINT`，金额统一 `BIGINT` 存**分（cent）**，包含 `deleted` / `create_time` / `update_time`。
- **订单基因分表（`t_order_0..3`）**：
  - 分片键：`user_id`（`user_id % 4`）；
  - 订单号 `order_no` 为基因法 Snowflake，按 `order_no` 查询通过 `GeneSnowflakeIdGenerator.resolveShardByOrderNo(orderNo, 4)` 精准定位单分片，零广播扫描；
  - 唯一防重索引：`UK(user_id, show_id, active_flag)`——创建有效单时 `active_flag = 1`，关单或退款完成后原子更新 `active_flag = order_no`，兼顾一人一场次一单与关单后合法重抢；
  - 选座扩展字段：`zone_id`、`seat_indices`（逗号分隔 Bitmap 偏移，自动配座为 `NULL`）。
- **电子票分表（`t_ticket_0..3`）**：与 `t_order` 同按 `user_id` 分片。
- **MQ 消费幂等表（`t_mq_consume_log`）**：携带 `user_id` 字段；单库多表场景与 `t_order_0..3` 同库，分库演进时配置为 `t_order` 的 Binding Table，确保与订单落库在同一本地事务提交。
- **实名隐私表（`t_user_identity`）**：
  - `real_name_cipher` / `id_card_cipher`：`AES-256-GCM`（12 字节随机 IV + 128 位认证标签）；
  - `id_card_hmac`：`HMAC-SHA256(idCard, serverPepper)`，建唯一索引 `uk_id_card_hmac` 防黄牛一证多号同时抵御低熵彩虹表。

## 2. 基因法 Snowflake 位宽契约

由 `com.starshow.common.id.GeneSnowflakeIdGenerator` 唯一实现：

```text
| 1bit 符号(0) | 41bit 毫秒时间戳 | 6bit workerId | 12bit sequence | 4bit userId基因(userId & 0x0F) |
```

- 严禁在外部用位覆盖的方式拼基因位（会引发同毫秒主键冲突）；
- 前端 JSON 必须依赖 `JacksonLongToStringConfig` 将 64 位 `Long` 序列化为 `String`，防止 JS `Number` 超过 $2^{53}-1$ 丢弃尾部基因位。

## 3. Redis Key 命名与 Hash Tag 契约

全部通过 `com.starshow.common.constant.RedisKeys` 生成，**严禁业务代码手拼字符串 Key**。

| 分类 | Key 模板 | Hash Tag | 说明 |
| --- | --- | --- | --- |
| 库存预扣 | `starshow:stock:{showId}:<tierId>` | `{showId}` | 售卖期常驻，禁被 DB 覆盖 |
| 已购集合 | `starshow:bought:{showId}` | `{showId}` | 场次限购防重 |
| 回查凭证 | `starshow:deduct_log:{showId}:<orderNo>` | `{showId}` | Hash, TTL 24h |
| 悬挂索引 | `starshow:deduct_pending:{showId}` | `{showId}` | ZSET(score=ts, member=orderNo) |
| 回补墓碑 | `starshow:rollback_done:{showId}:<orderNo>` | `{showId}` | String, TTL 24h，幂等+防悬挂 |
| 分区座位 | `starshow:seat:bitmap:{showId}:<zoneId>` | `{showId}` | Bitmap (0可选 / 1已占) |
| 场次/票档锁 | `starshow:lock:show:{showId}` / `starshow:lock:tier:{showId}:<tierId>` | `{showId}` | V2 / V3 策略锁 |
| 鉴权黑名单 | `starshow:auth:blacklist:<jti>` | 无 | 主动登出/踢人 |
| 预约布隆/计数 | `starshow:rsv:bloom:<showId>` / `starshow:rsv:count:<showId>` | 无 | 链路一蓄水 |
| 演出详情 | `starshow:show:detail:<showId>` | 无 | 逻辑过期 JSON |

## 4. RocketMQ 5.x Topic 与延迟消息契约

全部常量定义在 `com.starshow.api.constant.MqTopics`：

| Topic | 消息类型 | Consumer Group | 关键注意 |
| --- | --- | --- | --- |
| `TOPIC_SHOW_REMIND` | 任意时间延迟消息 | `cg-remind-notify` | Broker 已启 `timerWheelEnable=true`；使用 Remoting 客户端时设置 `message.setDeliverTimeMs(ts)`，勿再用 4.x 固定 18 级 `delayLevel` |
| `TOPIC_ORDER_CREATE` | 事务消息 | `cg-order-create` | 半消息 + Lua 本地事务 + 回查墓碑 |
| `TOPIC_ORDER_TIMEOUT` | 15 分钟延迟消息 | `cg-order-timeout` | 关单前主动查支付单 |
| `TOPIC_TICKET_ISSUE` | 事务/可靠消息 | `cg-ticket-issue` | 幂等生成电子票 |
| `TOPIC_REFUND_EVENT` | 按 `orderNo` 顺序消息 | `cg-refund-saga` | 驱动退款 Saga |
