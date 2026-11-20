# StarShow（星演票务）AI 协同开发与架构约束规范 (AGENTS.md)

本文件固化项目最高优先级的架构红线、数据契约与编码规范。所有后续开发（Phase 1 ~ Phase 6）必须严格遵守，禁止偏离。

> **项目级专属 Skills 与文档治理入口（按需联动）**：
> - 开发宪法与渐进式参考路由：`.agents/skills/starshow/SKILL.md`
> - 实证验收与测试防弱化门禁：`.agents/skills/prove-it/SKILL.md`（任何代码改动与里程碑交付前强制执行）
> - 文档治理总导航与进度账本：`docs/README.md`、`docs/PROGRESS.md`（跨会话接续第一入口）

---

## 1. 工程结构与依赖方向红线

- **包名根路径**：`com.starshow`
- **编译基线**：Java 21 LTS（`maven.compiler.release=21`），Spring Boot `3.3.5`，Spring Cloud `2023.0.3`，Spring Cloud Alibaba `2023.0.1.2`。
- **单向依赖红线（严禁循环依赖）**：
  - `starshow-common`：纯底座，不依赖任何业务模块。
  - `starshow-api`：仅依赖 `starshow-common`，定义 Facade 接口、DTO 与 MQ Event 契约。
  - 业务服务（`user` / `show` / `order` / `payment`）：依赖 `starshow-common` + `starshow-api`。
  - **双运行形态约束**：业务间同步调用统一面向 `starshow-api` 中的 `*Facade` 接口。在 `starshow-boot-all` 单进程聚合模式（`spring.profiles.active=monolith`）下直接注入本地 Bean，**严禁单进程内通过 HTTP OpenFeign 自调用触发 Tomcat 线程池死锁**；分布式模式（`distributed`）下切换为 OpenFeign 代理实现。

---

## 2. 强制固化架构决策（已拍板，禁回退）

1. **冷热数据严格分治（严禁覆盖实时库存）**
   - 静态/低频元数据（演出、场次、票档价格、开票时间）：MySQL 为事实源 → Caffeine (L1) + Redis (L2 逻辑过期) + 事件失效。
   - 开票售卖期实时库存与座位图（`starshow:stock:*`、`starshow:seat:bitmap:*`）：开票前通过 Preheat 预热入 Redis，售卖窗口期内以 Redis 为准事实源，**严禁被 DB 回写或 Canal 删缓存覆盖**，最终一致性由对账任务收敛。

2. **Redis Key 命名与 Cluster Hash Tag 规范（防 `CROSSSLOT`）**
   - 同一场次参与同一段 Lua 脚本的 Key，**必须共享 `{showId}` 作为唯一 Hash Tag**，其他动态维度**禁止使用花括号 `{}`**。
   - 标准模板：
     - `starshow:stock:{showId}:<tierId>` (String/Int，售卖期常驻)
     - `starshow:bought:{showId}` (Set，已购 userId)
     - `starshow:seat:bitmap:{showId}:<zoneId>` (Bitmap，0 可选 / 1 已占)
     - `starshow:deduct_log:{showId}:<orderNo>` (Hash，TTL 24h，事务回查凭证)
     - `starshow:deduct_pending:{showId}` (ZSET，score=扣减时间戳秒，member=orderNo，悬挂扣减扫描索引)
     - `starshow:rollback_done:{showId}:<orderNo>` (String，TTL 24h，回补幂等 + 防悬挂墓碑)
     - `starshow:auth:blacklist:<jti>` (String，JWT 黑名单)
     - `starshow:rsv:bloom:<showId>` / `starshow:rsv:count:<showId>`
     - `starshow:show:detail:<showId>` (逻辑过期 JSON)

3. **抢票终版（V4）事务消息 + 防悬挂闭环**
   - 顺序：发 RocketMQ Half 消息 → 本地事务执行 `seckill_deduct.lua`（或 `seckill_seat_deduct.lua`）→ 成功则 `COMMIT`，库存不足/重复/命中墓碑则 `ROLLBACK`。
   - **防悬挂**：Broker 回查 `checkLocalTransaction` 时查 `EXISTS starshow:deduct_log:{showId}:<orderNo>`；存在则 `COMMIT`；不存在时必须先写墓碑 `SET starshow:rollback_done:{showId}:<orderNo> 1 NX EX 86400` 再返回 `ROLLBACK`，阻止延迟到达的 Lua 造成孤立扣减。
   - **悬挂扫描**：每 5 分钟对账任务仅通过 `ZRANGEBYSCORE starshow:deduct_pending:{showId}` 拉取超过 5 分钟未清理的 `orderNo`，**禁止使用 `KEYS` 或全库 `SCAN`**。

4. **基因法 Snowflake 与前端精度防丢**
   - 64 位位宽严格分配：`1bit 符号(0) | 41bit 毫秒时间戳 | 6bit 机器ID | 12bit 序列号 | 4bit 基因位(userId & 0x0F)`。**严禁覆盖序列号位**。
   - Jackson 全局强制将 `Long` / `BigInteger` 序列化为 JSON `String`，杜绝 JS 精度丢失破坏尾部基因位。

5. **MySQL 分片、本地事务与行锁保护**
   - 所有金额字段统一 `BIGINT` 存**分（cent）**，严禁 `FLOAT/DOUBLE`。
   - 订单表 `t_order_0..3` 按 `user_id % 4` 基因分片；唯一防重约束为 `UK(user_id, show_id, active_flag)`（有效单 `active_flag = 1`，关单/退票后更新为 `order_no`，保证合法重抢）。
   - `t_mq_consume_log` 在单库多表下保持同库单表（分库演进时必须配置为 `t_order` 的 Binding Table 按 `user_id` 路由），保证消费幂等记录与订单落库在**同一本地事务**内提交。
   - V4 消费端更新 `t_ticket_tier.available_stock` 时，必须通过消费并发限流或批量聚合扣减，防止 InnoDB 单行热点锁拖垮 DB。

6. **订单状态机与支付/关单竞速底线**
   - 所有订单状态更新必须在 SQL `WHERE` 中显式携带期望原状态（数据库级 CAS）：`WHERE order_no = ? AND status = ?`。
   - 订单一旦 CAS 进入 `CLOSED`，**严禁复活回 `PAID`**；滞后支付回调必须自动转入异常支付流水并触发原路退款。
   - Mock 支付单默认过期时间 `14m30s`，业务超时关单延迟 `15m`（错峰 30s）；同时保留 `?fault=RACE_WITH_CLOSE` 开关用于演练极端竞速。

7. **安全与隐私红线**
   - 身份证号加密存储采用 `AES-GCM`，唯一校验哈希必须使用 **`HMAC-SHA256(idCard, serverPepper)`**（严禁裸 `SHA-256` 遭彩虹表反推）。
   - 密钥与密码严禁硬编码进源码或提交至 Git，统一通过环境变量注入并提供 `.env.example`。

8. **压测与验收口径**
   - V1 → V4 对比压测必须使用同一套 5,000+ 独立用户 JWT Token 池。
   - V4 压测报告必须同时给出 **API 受理 TPS / P99** 与 **MQ E2E 落库排空总耗时（Drain Time）**，并跑通零超卖、零重复单自动断言。
