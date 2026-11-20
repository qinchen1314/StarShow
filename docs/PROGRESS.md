# StarShow 长周期开发进度账本 (PROGRESS.md)

> **跨会话接续第一入口**：每次开启新开发会话，先读本页即可掌握当前进度、已验证能力、挂起事项与下一步指令。

---

## 一、里程碑总览

| 阶段 | Git Tag | 状态 | 核心目标 | 实证验收状态 (`prove-it`) |
| --- | --- | --- | --- | --- |
| **Phase 0** | `v0.1.0-infra` | ✅ 已完成并上云 | 多模块骨架、Docker 分层栈、MySQL 14 表 DDL+Seed、3 套 Lua 契约、`starshow-common` 底座、项目级 Skills 与文档治理 | L1 全绿（9/9 编译，6/6 单测，脱敏 0 命中） |
| **Phase 1** | `v0.2.0-chain1`（待打） | ✅ 已完成（含 Phase 1.1 优化全绿） | 链路一：JWT 黑名单、AES+HMAC 实名防黄牛、布隆预约蓄水、有界收敛+SingleFlight 计数、RocketMQ 5.x 延迟提醒、站内通知同事务落账、主办方改期版本联动、我的预约与实名前置引导、双形态 OpenFeign | 全绿（9/9 编译，59/59 常规测试，5/5 真实 Docker 中间件并发/故障/改期/SingleFlight 测试，双形态原包隔离 E2E + 两进程中断恢复 + 哨兵完好 + 脱敏 0 命中；`ISSUE-001`~`010` 全部关闭）。见 [Phase 1.1 实证回执](./issues/ISSUE-007-chain1-acceptance-evidence.md#2026-11-20-链路一深度优化phase-11实证回执)。 |
| **Phase 2** | `v0.3.0-chain2` | ⬜ 未开始 | 链路二：Caffeine L1 + Redis L2 逻辑过期、分区 Bitmap 座位图、开票 Preheat 预热 | 待验收 |
| **Phase 3** | `v0.4.0-chain3` | ⬜ 未开始 | 链路三：V1→V4 策略抢票引擎、Lua 预扣/选座、RocketMQ 事务消息防悬挂回查、基因分表 | 待验收 |
| **Phase 4** | `v0.5.0-chain4` | ⬜ 未开始 | 链路四：Mock 支付故障注入、15min 关单竞速、出票履约、退款 Saga、悬挂+T+1 四方对账 | 待验收 |
| **Phase 5** | `v0.9.0-bench` | ⬜ 未开始 | 5,000+ Token 池真实 V1→V4 压测（含 V4 E2E 排空耗时）与 Grafana 观测报告 | 待验收 |
| **Phase 6** | `v1.0.0-release` | ⬜ 未开始 | 零构建单页演示台 (`web-console/`)、完整开源 README 与 GitHub Actions 全绿发布 | 待验收 |

---

## 二、当前环境与已验证能力

- **已验证通过**：
  - 本机编译环境：JDK 25 + `--release 21` + Lombok `1.18.42` + ByteBuddy `1.17.5` + Mockito `5.17.0` + Spring Boot `3.3.5`（9/9 模块全绿）。
  - 链路一（[ISSUE-001](./issues/ISSUE-001-chain1-user-reservation.md)）全部 9 项整改与优化 Issue 已闭环通过实跑验证（0 未决缺口）：
    - [ISSUE-002](./issues/ISSUE-002-chain1-required-secrets.md)：移除全部公开默认密钥/密码，4 个发行包缺失或使用公开占位值均拒绝启动，报错仅含配置项名；`docker-compose.yml` 强制要求环境变量注入。
    - [ISSUE-003](./issues/ISSUE-003-chain1-reservation-count-consistency.md) & [ISSUE-008](./issues/ISSUE-008-chain1-reservation-count-crash-recovery.md)：单 Key Hash `{epoch,count}` + Lua `CAS-INCR`（不续期 TTL，默认 30s 有界回源收敛）+ 分布式锁与 Fencing Token 防租约失效旧快照覆盖；通过冷启动并发、GET/POST 同步屏障、实例内故障、锁租约超期旧覆盖拦截与双形态两进程强杀写入者恢复验证。
    - [ISSUE-004](./issues/ISSUE-004-chain1-reminder-recovery.md) & [ISSUE-009](./issues/ISSUE-009-chain1-reminder-recovery-starvation.md)：提醒状态机四态单向 CAS（`0/2 -> 3`、`0/2 -> 2`、`0/2/3 -> 1`，终态 `1` 禁回退）+ `next_retry_time` 字段与 `idx_remind_retry` 索引 + 游标分页积压消化 + 单场次元数据故障隔离；真实 MySQL 8 + RocketMQ 5.3 验证 120 条超窗记录与故障场次均不饥饿阻塞跨场次到期提醒。
    - [ISSUE-005](./issues/ISSUE-005-chain1-distributed-facade.md)：`distributed` 模式接入受控 OpenFeign（`ShowServiceFeignClient` + 内部鉴权/TraceId 拦截器 + 显式超时）；`monolith` 模式仅注入本地 `ShowReadFacadeImpl` 零自调用。
    - [ISSUE-006](./issues/ISSUE-006-chain1-show-distributed-startup.md)：拆分 `ValidationExceptionHandler` 并补齐 `starshow-show` validation 依赖，干净构建的 `starshow-show-exec.jar` 无需任何类路径补丁直接冷启动通过。
    - [ISSUE-007](./issues/ISSUE-007-chain1-acceptance-evidence.md) & [ISSUE-010](./issues/ISSUE-010-chain1-e2e-test-data-isolation.md)：固化隔离数据可复跑双形态 E2E 脚本 `scripts/verify-chain1-e2e.ps1`（拒绝默认 `starshow` 库与 Redis DB 0，自动建/毁隔离资源，中途失败非零退出且全量回收进程与资源，默认库哨兵 100% 完好）。
    - **Phase 1.1 深度优化**（承接 [四条票务链路优化评审](./reviews/chain-optimization-review.md) 与用户体验补强）：落地 `GET /api/v1/reservations/me`（我的预约列表，按开售时间由近到远排序）、`GET /api/v1/reservations/{showId}/me`（本人状态+实名前置 `VERIFY_IDENTITY`+`serverTime` 校准+最新开售时间/版本）、提醒 5 态拆分（`1 DELIVERED` vs `4 SKIPPED`）+ `t_user_notification` 同事务幂等落账与改期即时 `SCHEDULE_CHANGED` 站内变更通知、主办方改期 `sale_config_version` 乐观 CAS 推进与预约重排（旧版本晚到延迟消息版本守卫丢弃）、`ReservationCountStore` 同场次 SingleFlight 回源合并 + 回源指标 + 故障陈旧微快照降级 + 错峰抖动提醒。
  - 真实 Docker Core 栈（MySQL 8.0.39 / Redis 7.4 / RocketMQ 5.3.0 / Nacos v2.4.3）健康运行。
  - 远程仓库推送：已配置仓库级脱敏身份（noreply 邮箱）与独立凭据隔离，推送畅通。

---

## 三、下一阶段执行清单

1. Phase 1（链路一）已全部实证通过，待用户确认后提交/合并分支 `feat/chain1-user-reservation` 至 `main` 并打 `v0.2.0-chain1` Tag。
2. Phase 2、3、4 的完整设计规格已写入 [演出浏览与选座](./domains/02-show-inventory.md)、[抢票与下单](./domains/03-order-seckill.md)、[支付、退款和对账](./domains/04-payment-reconcile.md)，并同步 [接口总契约](./contracts/API_CONTRACT.md)。下一阶段优先评审 Phase 2 的改期版本、预热完成条件和开售门禁，评审通过后按顺序实施和验收。
