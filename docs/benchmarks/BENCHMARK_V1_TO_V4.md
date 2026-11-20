# V1 → V4 抢票引擎真实压测实验报告（Phase 5 填充模板）

> 原则：严禁编造天文数字 QPS。所有数据必须在同一本机硬件、同一 5,000+ 独立 JWT Token 池、同一 10,000 张票池下实测得出，并附带自动一致断言结果。

## 1. 实验环境与控制变量

- **机器配置**：`待 Phase 5 实测填写（CPU 核数 / 内存 / OS）`
- **JVM 参数**：`待填写（-Xms / -Xmx / GC）`
- **中间件配置**：MySQL 8.0（连接池大小 / Buffer Pool）、Redis 7.4、RocketMQ 5.3.0
- **压测数据集**：演出 `showId=10001`（总库存 10,000 张），独立压测用户 JWT Token 池 5,000+（避免被单用户防重挡在入口导致测出虚假吞吐）
- **压测工具**：k6 / JMeter（脚本位于 `load-test/`）

## 2. V1 → V4 对比结果表（实测填充区）

| 版本 | 核心方案 | 并发线程 | API 受理 TPS | Avg RT (ms) | P95 RT (ms) | P99 RT (ms) | V4 E2E 落库排空耗时 | 超卖数 | 重复单数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| V1 | MySQL 乐观锁同步落单 | - | - | - | - | - | N/A | 0 | 0 |
| V2 | Redisson 场次级粗锁 | - | - | - | - | - | N/A | 0 | 0 |
| V3 | Redisson 票档级细锁 | - | - | - | - | - | N/A | 0 | 0 |
| V4 | Lua 无锁 + RocketMQ 事务消息 | - | - | - | - | - | - | 0 | 0 |

## 3. 压测后强制正确性断言（`prove-it` L4）

每次压测结束自动执行核对：
1. `Redis 已扣库存数 == MySQL t_order_0..3 有效订单总张数 <= 10,000`
2. `t_ticket_tier.available_stock == total_stock - MySQL 有效订单总张数`
3. `SELECT MAX(cnt) FROM (SELECT user_id, show_id, COUNT(*) cnt FROM t_order_* WHERE active_flag = 1 GROUP BY user_id, show_id) t <= 1`
4. `starshow:deduct_pending:{showId}` ZSET 排空后长度为 `0`
