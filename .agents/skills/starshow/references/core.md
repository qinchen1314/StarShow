# StarShow 核心原则与架构宪法

每次执行 `starshow-ticket` 任务前必须先读本文件。

## 1. 产品定位与核心哲学

StarShow 不是普通 CRUD 商城，也不是玩具级单机秒杀 Demo，而是面向「开票瞬间高并发洪峰 + 强资金/库存一致性」设计的工业级票务后端工程。

核心架构哲学：**一切为“开票那一秒”让路——分层削峰、冷热分治、越往下游流量越窄、强一致只留给库存和资金。**

## 2. 事实源（Source of Truth）严格分治

| 数据域 | 事实源 | 读写策略 | 禁止行为 |
| --- | --- | --- | --- |
| 演出/场次/票档价格/开票时间（静态/低频） | MySQL | Caffeine (L1) + Redis 逻辑过期 (L2) + 事件失效 | 禁止开票瞬间冷启动穿透打 DB（必须提前 Preheat） |
| 售卖期实时余票与座位占用（高频写） | Redis（售卖期准事实源） | Lua 原子扣减 / Bitmap 原子锁座；DB 物理库存做底线兜底；对账任务收敛 | **严禁 DB 回写/Canal 删缓存覆盖实时库存 Key** |
| 订单、支付流水、电子票、退款状态（资金域） | MySQL | 基因分片 + 状态机 CAS + 唯一索引 + 四方对账 | 严禁仅靠缓存判断资金与订单终态 |

## 3. 四版本演进原则（V1 → V4 同仓共存）

抢票核心引擎（`SeckillOrderStrategy`）必须在同一代码库内长期保留 V1 ~ V4 四个完整实现，通过配置 `starshow.seckill.strategy` 动态切换：

- `V1`：纯 MySQL `WHERE available_stock >= qty` 乐观锁同步下单（对照组，复现连接池打满）
- `V2`：Redisson 场次级粗粒度锁 + 同步落库（复现零超卖但串行瓶颈）
- `V3`：Redisson 票档级细粒度锁 + 同步落库（复现吞吐提升但仍有锁竞争与 RTT）
- `V4`：Redis Lua 无锁原子预扣（自动配座 / Bitmap 连座）+ RocketMQ 事务消息 + 防悬挂墓碑 + 三层幂等异步落库（生产终版）

任何新功能或重构不得破坏 V1 ~ V3 的可运行性，确保随时可用同一套脚本复现 V1 → V4 真实压测曲线。

## 4. 实施优先级（Phase 0 → Phase 6）

严格按阶段闭环推进，每阶段必须经 `prove-it` 验收通过并打 Git Tag 后再进入下一阶段：

1. `Phase 0 (v0.1.0-infra)`：骨架、Docker 分层栈、MySQL 14 表 DDL + Seed、3 套 Lua 契约、`starshow-common` 底座
2. `Phase 1 (v0.2.0-chain1)`：链路一（JWT 黑名单 + AES/HMAC 实名防黄牛 + 布隆预约 + MQ 5.x 延迟提醒）
3. `Phase 2 (v0.3.0-chain2)`：链路二（L1+L2 逻辑过期缓存 + 分区 Bitmap 座位图 + Preheat 预热）
4. `Phase 3 (v0.4.0-chain3)`：链路三（V1→V4 策略引擎 + 事务消息防悬挂回查 + 基因分表）
5. `Phase 4 (v0.5.0-chain4)`：链路四（Mock 支付故障注入 + 15min 关单竞速 + 出票 + 退款 Saga + 四方对账）
6. `Phase 5 (v0.9.0-bench)`：5,000+ 独立 Token 池真实 V1→V4 压测（含 V4 E2E 排空耗时）
7. `Phase 6 (v1.0.0-release)`：零构建演示台 + 开源打磨

## 5. 开源脱敏铁律

本项目公开托管在 GitHub，任何提交前必须确保：
- 零真实密钥/密码/Token（全量环境变量占位 + `.env.example`）；
- 零本机绝对路径、零代理配置入库、零真实个人邮箱（使用 GitHub noreply 邮箱）。
