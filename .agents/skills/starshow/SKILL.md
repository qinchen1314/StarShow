---
name: starshow
description: >-
  StarShow（星演票务）项目开发宪法与架构路由 Skill。用于 starshow-ticket 仓库内的全部后端开发、
  四条核心业务链路（实名防黄牛预约、多级缓存与分区 Bitmap 选座、V1→V4 演进式抢票引擎、
  Mock 支付故障注入/超时关单竞速/退款 Saga/四方对账）、Redis Lua 原子脚本、RocketMQ 5.x 事务与延迟消息、
  MySQL 基因分表、单进程/微服务双形态协调、代码审查与脱敏发布。任何改码前必须先通过本 Skill 门禁，
  交付前必须联动 `prove-it` Skill 完成实证验收。
---

# StarShow（星演票务）项目开发宪法 Skill

将本 Skill 视为 `starshow-ticket` 仓库的最高工程宪法。动手写代码前，先按**业务链路、分层归属、冷热数据域、并发/幂等影响面、验收证据级别**对需求进行分类。

## 必读第一步（按需渐进式披露）

每次开始任务前，**必须先读** `references/core.md`。

随后按任务类型**只按需加载**相关的参考文档（避免上下文膨胀）：

- 高并发读/写演进、Lua 原子脚本、事务消息、关单竞速与退款 Saga：`references/concurrency-consistency.md`
- MySQL 14 表契约、基因法 Snowflake、Redis Key Hash Tag、RocketMQ Topic：`references/storage-contracts.md`
- Spring Boot 分层、单进程/微服务双形态 Facade、统一响应与 Maven 命令：`references/spring-boot-api.md`
- ✅ / ❌ 正反代码范例（避坑速查）：`references/examples.md`
- 架构决策记录（ADR 索引与规则冲突升级机制）：`references/adr.md`
- 代码审查与开源脱敏检查清单：`references/review-checklist.md`
- **所有代码改完后交付前（强制）**：加载并执行同级目录的 `prove-it` Skill（`../prove-it/SKILL.md`）

## 编码前 9 问自检门禁

在修改任何代码前，先在脑中或工作笔记里逐条确认：

1. **链路归属**：本次改动属于哪条链路（链路一预约 / 链路二浏览选座 / 链路三抢票 V1~V4 / 链路四支付履约对账 / 公共基建）？
2. **模块与分层**：归属哪个 Maven 模块与分层？是否满足严格单向依赖（无循环依赖）？
3. **冷热边界**：触碰的是静态/低频元数据（MySQL 为事实源），还是售卖期实时库存/座位 Bitmap（Redis 为准事实源）？会不会误用 DB 回写覆盖 Redis 实时库存？
4. **Cluster Slot 安全**：新增或修改的 Redis Lua Key 是否全部共享 `{showId}` 单一 Hash Tag，确保零 `CROSSSLOT`？
5. **事务与分片边界**：涉及 `t_order` 落库与 `t_mq_consume_log` 幂等写入时，是否仍处于同一本地事务？是否依赖了跨分片查询？
6. **状态机单向性**：订单/支付/退款状态更新是否做了 SQL 级 CAS（`WHERE status = ?`）？是否杜绝 `CLOSED` 复活回 `PAID`？
7. **双运行形态**：跨模块同步调用是否走 `starshow-api` 的 `*Facade` 接口，防止 `starshow-boot-all` 单进程模式下 HTTP 自调用拖死线程池？
8. **ADR 冲突**：是否与既有 ADR（ADR-001 ~ ADR-009）或 Non-Negotiables 冲突？若冲突必须先走 `references/adr.md` 升级，绝不静默绕开。
9. **Prove-It 验收面**：哪些单测、并发测试、魔鬼故障剧本或运行态命令能客观证明这次改动真正生效且未弱化原有断言？

## 不可妥协的工程红线

- 严禁在 Controller 中编写业务逻辑、直接操作 Mapper/Repository 或开启事务。
- 严禁在售卖窗口期内通过 Canal / 延时双删 / DB 回写覆盖 Redis 实时库存 Key（`starshow:stock:*`）与座位 Bitmap（`starshow:seat:bitmap:*`）。
- 严禁在同一段 Lua 脚本中混用不同的 Redis Cluster Hash Tag（同一场次原子操作统一绑定 `{showId}`）。
- 严禁使用 `KEYS *` 或全库 `SCAN` 扫描悬挂扣减记录；必须通过 `ZSET` 索引（`starshow:deduct_pending:{showId}`）按时间戳范围拉取。
- 严禁覆盖基因法 Snowflake 的序列号位，严禁向前端直接输出未转 `String` 的 64 位 `Long` 订单号。
- 严禁把金额存成 `FLOAT` / `DOUBLE`（全库统一 `BIGINT` 存分）。
- 严禁在订单状态机中允许 `CLOSED` 复活回 `PAID`；关单后滞后支付必须自动落异常单并触发原路退款。
- 严禁使用裸 `SHA-256(idCard)` 存身份证唯一索引；必须使用 `HMAC-SHA256(idCard, serverPepper)`，原文使用 `AES-256-GCM` 随机 IV 加密。
- 严禁在 `starshow-boot-all` 单进程模式下通过 HTTP OpenFeign 自调用同进程接口。
- 严禁将任何真实密码、密钥、个人邮箱、本机绝对路径或代理配置提交至 Git 仓库。
- 严禁跳过 `prove-it` 验收直接宣称任务完成，严禁弱化或删除已有测试断言来换取假绿灯。

## 标准开发与验收工作流

1. 阅读 `references/core.md` 及本次任务对应的参考文档。
2. 审阅现有代码与 `docs/ARCHITECTURE.md`，通过编码前 9 问自检门禁。
3. 严格按分层落地实现（Controller → Application Service / Strategy → Domain/StateMachine → Infrastructure Mapper/Lua/MQ）。
4. 编写与风险等级相匹配的测试（单测 / 并发零超卖 / 故障注入剧本）。
5. **激活 `prove-it` Skill**：实跑测试与运行时验证、检查 `git diff`（防测试弱化 + 脱敏扫描）、输出结构化《实证验收回执》。
6. 对照 `references/review-checklist.md` 完成最终自查后交付。

## 全链路演进蓝图

```text
[链路一: 蓄水防黄牛] 注册登录(JWT黑名单) -> AES+HMAC实名一证一号 -> 布隆预约 -> MQ 5.x 延迟开票提醒
        │
[链路二: 极速读洪峰] 开票前Preheat预热 -> L1(Caffeine)+L2(Redis逻辑过期) -> 分区Bitmap实时座位图
        │
[链路三: V1->V4抢票] 网关限流+防重Token -> 策略切换(V1乐观锁/V2场次锁/V3票档锁/V4 Lua+事务消息+防悬挂) -> 基因分表三层幂等落单
        │
[链路四: 资金与底网] Mock支付(故障注入) -> 15min关单竞速(查单防错关/滞后转退款) -> 出票履约 -> 退款Saga -> 悬挂+T+1四方对账
```
