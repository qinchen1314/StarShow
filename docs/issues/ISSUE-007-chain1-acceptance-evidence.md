---
issue_id: ISSUE-007
title: "[P2][Phase 1] 统一验收口径并保存可复跑的证据"
type: Task
priority: P2
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, prove-it, docs]
---

# [P2][Phase 1] 统一验收口径并保存可复跑的证据

## 修复与验收

- [x] 在仓库中固化隔离数据可复跑双形态验收脚本 [scripts/verify-chain1-e2e.ps1](../../scripts/verify-chain1-e2e.ps1)（支持 `-Mode monolith`、`-Mode distributed`、`-VerifyMissingSecrets`、`-VerifyCrashRecovery`、`-SimulateMidwayFailure`；零写死密钥，强制隔离 MySQL 库与 Redis DB `1..15`，绝不触碰默认 `starshow` 库与 Redis DB 0）。
- [x] 下方终版复验回执明确区分真实组件与 Mock/H2 桩、环境、执行命令与断言结果，完整覆盖 `ISSUE-002` ~ `ISSUE-010` 全部关闭条件。
- [x] 同步 [账本](../PROGRESS.md)、[规格](../domains/01-user-reservation.md)、[接口契约](../contracts/API_CONTRACT.md) 与 [Epic](./ISSUE-001-chain1-user-reservation.md) 及全部子 Issue 状态，验收口径 100% 一致。

## 2026-11-20 链路一深度优化（Phase 1.1）实证回执

| 项目 | 实跑命令、真实/Mock 边界与判定 |
| --- | --- |
| **验收级别与范围** | L1 单元与单进程集成 + 真实 Docker 中间件 5 项测试 + 双形态原始发行包隔离 E2E（覆盖 [四条票务链路优化评审](../reviews/chain-optimization-review.md) 链路一全部 4 项优化及 `ISSUE-001` ~ `ISSUE-010` 回归）。 |
| **L1 & 聚合集成（无外部依赖）** | `mvn -B clean package` / `mvn -B clean test`（Windows 11 / JDK 25 `--release 21`）→ **9/9 模块 `BUILD SUCCESS`，常规测试 59/59 全绿（0 Failures, 0 Errors）**；新增覆盖本人预约生命周期查询（含未实名 `VERIFY_IDENTITY` 与 `serverTime`）、我的预约列表排序、提醒 `4 SKIPPED` 语义拆分、旧版延迟消息版本守卫丢弃、改期即时 `SCHEDULE_CHANGED` 站内通知、32 并发 SingleFlight 回源合并（`dbFallbackCount == 1`）、Redis+DB 双抖动陈旧快照与过旧暂隐、主办方改期乐观 CAS；`Chain1LiveMiddlewareVerificationTest`（5 项）离线按条件跳过。 |
| **真实中间件 5 项测试（MySQL 8 + Redis 7 + RocketMQ 5.3）** | 在独立隔离库 + Redis DB 14 上执行 `mvn -B -pl starshow-boot-all -am test "-Dtest=Chain1LiveMiddlewareVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dstarshow.live.enabled=true"` → **`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`**（原有 `issue003`/`004`/`008`/`009` 全绿，新增 `phase11` 实证：推迟改期 `v1→v2` 即时落 1 条 `SCHEDULE_CHANGED` 通知且重放幂等、真实 RocketMQ 投递的旧 v1 延迟消息被版本守卫拦截跳过、`v2→v3` 真实送达并同事务落 1 条 `REMIND_BEFORE_SALE` 通知且重放仍为 1 条、提前改期至已开售置 `4 SKIPPED` 且 `/me` 返 `entryOpen=true, ENTER_SECKILL`、24 并发缓存缺失在真实 Redis+MySQL 上合并为 1 次 DB 回源）。 |
| **双形态原始发行包隔离 E2E + 哨兵校验** | 预置默认库哨兵后连续执行 `verify-chain1-e2e.ps1`（`monolith -VerifyMissingSecrets -VerifyCrashRecovery`、`distributed -VerifyCrashRecovery`、`-SimulateMidwayFailure`、以及默认库/DB0 拒绝校验）→ 全部 `[PASS]`：19 张物理表初始化、双形态均通过网关验证未实名 `/reservations/10003/me` 返 `VERIFY_IDENTITY`、预约后 `/reservations/me` 列表可见、`/notifications/me` 同事务落账与已读、`PUT /internal/shows/10003/schedule` + `POST /internal/reservations/shows/10003/reschedule`（跨 OpenFeign 生效并即时落 `SCHEDULE_CHANGED` 通知）、两进程中断计数恢复及默认库哨兵 100% 完好。 |
| **Diff 防弱化 & 脱敏扫描** | Phase 0 原有 3 个测试文件 `git diff` 为空（0 弱化）；全量追踪与未追踪文件扫描 0 真实凭据、0 公开默认密钥回退、0 个人邮箱、0 本机路径、0 代理配置，`.env` 未被跟踪。 |
| **最终结论** | **✅ PASS：四条链路优化方案已全部落盘，链路一 Phase 1.1 优化与原有 Phase 1 门禁全部实证通过。** |

---

## 2026-11-20 终版复验回执（ISSUE-008 ~ ISSUE-010 闭环）

| 项目 | 实跑命令、真实/Mock 边界与判定 |
| --- | --- |
| **验收级别与范围** | L1 单元与不变量证明 + 单进程集成测试 + 真实 Docker 中间件并发/故障/积压测试 + 双形态原始发行包隔离数据 E2E（覆盖链路一全部节点与 `ISSUE-002` ~ `ISSUE-010`）。 |
| **L1 & 聚合集成（无外部依赖）** | `mvn -B clean package` / `mvn -B clean test`（Windows 11 / JDK 25 `--release 21`）→ **9/9 模块 `BUILD SUCCESS`，常规测试 51/51 全绿（0 Failures, 0 Errors）**；`Chain1LiveMiddlewareVerificationTest`（4 项）按 `-Dstarshow.live.enabled=true` 在离线模式跳过。 |
| **真实中间件 4 项故障与并发测试（ISSUE-003/004/008/009）** | 直连真实 Docker MySQL 8 (`13306`，隔离库) + Redis 7 (`6379`，隔离 DB 14) + RocketMQ 5.3 (`9876`) 运行 `mvn -B -pl starshow-boot-all -am test "-Dtest=Chain1LiveMiddlewareVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dstarshow.live.enabled=true"` → **`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`**（真实 MySQL 8 + Redis 7 + RocketMQ 5.3；仅通知计数器、锁租约挂起钩子与单场次元数据故障注入使用测试桩）：<br/>• `issue003`：20 线程冷启动并发、删键后 POST/GET 同步屏障防多计、瞬时 Redis 故障脏标记收敛、16 线程同用户防重全绿。<br/>• `issue004`：超 72h 入窗补投（`0->3->1`）、Broker 故障恢复（`2->3->1`）、双到+重放终态 `1` 禁回退且通知严格 `== 1`。<br/>• `issue008`：Fencing Token 阻断锁租约超期旧重建者 A 覆盖较新重建者 B；DB 已提交而 Redis 未更新场景在 2s 有界 TTL 内自动回源收敛。<br/>• `issue009`：120 条超窗记录跨 2 批占满小 ID + 1 条故障元数据场次 + 2 条跨不同场次到期失败记录，单轮 `recoverDueReminders(50, 10)` 后超窗记录推迟 `next_retry_time` 维持 `0`、故障场次隔离退避、2 条跨场次到期提醒经真实 RocketMQ Broker 消费全部收敛为 `1` 且通知各严格 `== 1`。 |
| **双形态原始发行包隔离 E2E + 两进程中断恢复 + 哨兵校验（ISSUE-002/005/006/008/010）** | 在默认 MySQL `starshow`（`t_user.id=999990001`）与 Redis DB 0（`starshow:sentinel:issue010=alive`）预置哨兵后连续执行：<br/>1. 传 `-TestDatabase starshow` 与 `-TestRedisDb 0` 均在任何写入前 Fail-Fast 拒绝（退出码 `1`）；<br/>2. `scripts/verify-chain1-e2e.ps1 -Mode monolith -VerifyMissingSecrets -VerifyCrashRecovery -TestRedisDb 15` → 4/4 原始发行包缺密拒绝启动 + 完整链路（状态 `3->1` 耗时 `6s`）+ 两进程（`boot-all:8090` 与 `peer:8012`）强杀写入者后 4s 内收敛为 `1`、重启重试返 `A0106` 且 `MySQL == Redis == GET == 1`；<br/>3. `scripts/verify-chain1-e2e.ps1 -Mode distributed -VerifyCrashRecovery -TestRedisDb 15` → 零类路径补丁 `starshow-show-exec.jar:8002` + OpenFeign `starshow-user-exec.jar:8001` + `gateway:8000` 完整链路（状态 `3->1` 耗时 `5s`）+ 两进程（`user:8001` 与 `peer:8012`）强杀写入者后有界收敛、重启重试返 `A0106` 且 `MySQL == Redis == GET == 1`；<br/>4. `-SimulateMidwayFailure` 实证非零退出码（`1`）、隔离库自动删除、Redis DB 15 `DBSIZE == 0`、所有端口释放；<br/>5. 复查默认 MySQL `starshow` 与 Redis DB 0 哨兵 100% 完好并清理哨兵。 |
| **Diff 防弱化 & 脱敏扫描** | Phase 0 原有 3 个测试文件（`CryptoAndRedisKeysTest`、`GeneSnowflakeIdGeneratorTest`、`OrderStateMachineTest`）`git diff` 为空（0 弱化）；全量追踪与未追踪文件扫描 0 真实凭据、0 公开默认密钥回退、0 个人邮箱、0 本机路径、0 代理配置，`.env` 未被跟踪。 |
| **最终结论** | **Phase 1（链路一）终版复验全部通过，`ISSUE-001` ~ `ISSUE-010` 全部关闭（0 个未决 P0/P1/P2 缺口），具备合并 `main` 并打 `v0.2.0-chain1` Tag 条件。** |

---

## 2026-11-20 独立复核（历史记录，3 项 P1 已于上方闭环）

| 项目 | 本轮证据 |
| --- | --- |
| 验收级别与范围 | L1、真实中间件集成及双形态发行包 E2E。覆盖链路一，未涉及库存或支付。 |
| L1 | 使用本机 JDK 25 和同版本 Mockito Java agent 执行 `mvn -B clean package`。9 个模块构建成功，常规测试 51 项通过，0 失败、0 错误。真实中间件测试 2 项在这一步按条件跳过。 |
| 真实中间件 | 对隔离 MySQL 库和 Redis 测试库执行 `mvn -B -pl starshow-boot-all -am test "-Dtest=Chain1LiveMiddlewareVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dstarshow.live.enabled=true"`。2 项通过，0 失败、0 错误、0 跳过。 |
| 原始发行包 | 网关、聚合、用户和演出 4 个原包缺少必要密钥时均拒绝启动。注入独立凭据后，单进程和分布式均通过注册、登录、实名、预约与登出。真实 RocketMQ 使 MySQL 提醒状态 `3 → 1`，两种部署分别约 6.0 秒和 5.4 秒。网关与下游直连均拒绝登出后的 JWT。 |
| 新增故障证据 | 计数缓存预热后只提交 MySQL 写入，观察到 `DB=1 / Redis=0 / GET=0`。100 条超窗预约占满补偿批次时，到期记录在 11 秒内保持 `2`，移开超窗记录后转为 `1`。见 [ISSUE-008](./ISSUE-008-chain1-reservation-count-crash-recovery.md) 和 [ISSUE-009](./ISSUE-009-chain1-reminder-recovery-starvation.md)。 |
| 验收隔离 | 原脚本直接清理 MySQL 默认库与 Redis 默认库。本轮用临时夹具操作独立测试库和初始为空的 Redis 测试库，测试完成后清理隔离数据。[ISSUE-010](./ISSUE-010-chain1-e2e-test-data-isolation.md) |
| Diff 与脱敏 | 已检查跟踪差异及未跟踪的实现和测试。既有 Phase 0 测试文件未变。敏感模式扫描未命中真实凭据、本机路径、个人邮箱或代理配置，`.env` 未跟踪且被忽略。 |
| 文档与结论 | 已更新链路一规格、接口契约、进度账本和 Epic。Phase 1 的常规测试和双形态主流程通过，三个 P1 缺口阻塞整体验收。 |

## 2026-11-20 首轮整改验收回执

| 项目 | 实跑命令、真实/Mock 边界与判定 |
| --- | --- |
| **验收级别** | L1 单元与不变量证明 + 单进程集成测试 + 真实 Docker 中间件并发与故障恢复集成测试 + 双形态原始发行包端到端联调 |
| **L1 & 聚合集成（无外部依赖）** | `mvn -B clean test`（Windows 11 / JDK 25 `--release 21`）→ **9/9 模块 `BUILD SUCCESS`，常规用例 51/51 全绿（0 Failures, 0 Errors）**；其中 `starshow-common` 18、`starshow-gateway` 7、`starshow-user` 22、`starshow-show` 3、`starshow-boot-all` 1（`Chain1MonolithIntegrationTest` 使用 H2 MySQL 模式 + 内存 Redis/MQ 桩，并断言 `monolith` 下零 OpenFeign 客户端）；`Chain1LiveMiddlewareVerificationTest`（2 项）按 `-Dstarshow.live.enabled=true` 开关在离线模式跳过。 |
| **真实中间件并发 & 故障恢复（ISSUE-003 & ISSUE-004）** | 直连真实 Docker MySQL 8 (`13306`) + Redis 7 (`6379`) + RocketMQ 5.3 (`9876`) 运行 `mvn -B -pl starshow-boot-all -am test "-Dtest=Chain1LiveMiddlewareVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dstarshow.live.enabled=true"` → **`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`**（100% 真实 MySQL 8 + 真实 Redis 7 + 真实 RocketMQ 5.3，仅通知计数器和瞬时故障注入点用测试 Spy/Notifier）：<br/>• `ISSUE-003`：20 线程冷启动并发预约（20）→ 删键后 POST 落库卡同步屏障被 GET 抢先回填（21，0 多计）→ 瞬时 Redis 故障触发脏标记并恢复收敛（22）→ 16 线程同用户并发重复预约（1 成功、15 次 `A0106`、计数 23），每步均实证 `Redis Hash == GET == MySQL COUNT(*)`。<br/>• `ISSUE-004`：超 72h 入窗补投（`0->3->1`）、Broker 故障恢复（`2->3->1`）、Broker 已收但发送端超时 + 补投双到 + 重放（终态 `1` 禁回退且通知次数严格 `== 1`）。 |
| **缺失凭据启动拒绝（ISSUE-002）** | `docker compose -f docker/docker-compose.yml config --quiet` 缺密退出码 `1`；`scripts/verify-chain1-e2e.ps1 -Mode monolith -VerifyMissingSecrets` 实跑 4 个干净构建原始发行 JAR（`starshow-gateway.jar`、`starshow-show-exec.jar`、`starshow-user-exec.jar`、`starshow-boot-all.jar`）缺密全部 `exit=1` 且仅报配置项名称。 |
| **单进程原始发行包 E2E** | `scripts/verify-chain1-e2e.ps1 -Mode monolith`（真实 Docker Core 栈 + `boot-all:8090` + `gateway:8000`，注入独立随机密钥）→ 全部断言 `[PASS]`：单值 `X-Trace-Id`、伪造头 401、未实名 `A0104`、实名零明文 + 64 位 HMAC + `A0105`、预约 `10003` 先查 DB 状态为 `3`（排期中）→ 计数 `1` → 重复 `A0106` → 6 秒后 RocketMQ 5.3 消费收敛为 `1` → 登出双端 `401`/`A0102`。 |
| **分布式原始发行包 E2E（ISSUE-005 & ISSUE-006）** | `scripts/verify-chain1-e2e.ps1 -Mode distributed`（真实 Docker Core 栈 + 原始 `starshow-show-exec.jar:8002` **零类路径补丁** + 原始 `starshow-user-exec.jar:8001` **OpenFeign** + `gateway:8000`）→ 全部断言 `[PASS]`：`/internal/shows/10003/basic` 无密钥 401/有密钥 200、跨进程 OpenFeign 预约 `10003` 先查状态 `3` → 5 秒后 RocketMQ 5.3 消费收敛为 `1` → 登出双端 `401`/`A0102`。 |
| **Diff 防弱化 & 脱敏扫描** | Phase 0 原有 3 个测试类（6 断言）`git diff` 为空（0 弱化）；全量 112 个变更/新增文件扫描 0 真实凭据、0 公开默认密钥回退、0 个人邮箱、0 本机路径、0 代理配置、`.env` 未跟踪。 |
| **最终结论** | **Phase 1（链路一）复验全部通过，`ISSUE-001` ~ `ISSUE-007` 全部关闭，具备合并 `main` 并打 `v0.2.0-chain1` Tag 条件。** |

---

## 历史记录：2026-11-20 初次复核回执（已整改关闭）

| 项目 | 实跑与判定 |
| --- | --- |
| 验收级别 | L1 + 链路一真实中间件集成。验收范围是 JWT、实名、预约和延迟提醒。 |
| L1 | 沙箱内 `mvn -B clean test` 在 Mockito 动态加载阶段停滞。以 Java agent 显式预加载同版本 Mockito 后，沙箱内 Tomcat 测试仍因本机回环权限报错。随后在获准使用回环网络的进程中执行 `mvn -B package`，9 模块成功，42 个测试，0 失败、0 错误、0 跳过。 |
| Core 栈 | `docker compose -f docker/docker-compose.yml config --quiet` 通过。使用现有 MySQL 8、Redis 7、RocketMQ 5.3，Nacos 容器处于运行中。把仓库 DDL 和种子导入本次隔离库，确认 18 张物理表及 3 个种子场次。 |
| 单进程原包 | `8090` 注册、登录、实名去重 `A0105`、未实名拒绝 `A0104`、预约和重复拒绝 `A0106` 均通过。MySQL 核验实名密文与 64 位十六进制 HMAC 字段，查询预约数为 `1`。未开票 `10003` 的短延迟提醒从 `0` 到 `1`，首次轮询观察到终态约 6.4 秒。登出后旧 JWT 返回 HTTP `401` / `A0102`，Redis 黑名单 TTL 与 JWT 剩余时长匹配。 |
| 分布式原包 | 已复现 `starshow-show` 独立启动失败，根因 `NoClassDefFoundError: jakarta/validation/ConstraintViolation`，详细命令见 [ISSUE-006](./ISSUE-006-chain1-show-distributed-startup.md)。 |
| 分布式诊断 | 临时补齐演出进程类路径后，独立网关（8000）、用户（8001）和演出（8002）的真实中间件主流程通过。预约数为 `1`，约 6.1 秒观察到提醒终态，登出后网关和直连用户服务均返回 HTTP `401` / `A0102`。 |
| Diff 和防弱化 | 审计了 `HEAD` 以来的跟踪变更与本次已有的未跟踪实现和测试。Phase 0 既有测试源无变更。本次复核只更新文档。 |
| 脱敏 | 扫描未发现真实密钥、个人邮箱、本机绝对路径或代理配置，`.env` 未跟踪且被忽略。找到能生效的公开默认凭据，见 [ISSUE-002](./ISSUE-002-chain1-required-secrets.md)，发布脱敏门禁未通过。 |
| 结论 | Phase 1 复核未通过。需关闭 [P0 配置](./ISSUE-002-chain1-required-secrets.md)、[计数一致性](./ISSUE-003-chain1-reservation-count-consistency.md)、[提醒补发](./ISSUE-004-chain1-reminder-recovery.md)、[分布式架构](./ISSUE-005-chain1-distributed-facade.md) 和 [发行包启动](./ISSUE-006-chain1-show-distributed-startup.md) 的缺口后复验。 |
