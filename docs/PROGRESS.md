# StarShow 长周期开发进度账本 (PROGRESS.md)

> **跨会话接续第一入口**：每次开启新开发会话，先读本页即可掌握当前进度、已验证能力、挂起事项与下一步指令。

---

## 一、里程碑总览

| 阶段 | Git Tag | 状态 | 核心目标 | 实证验收状态 (`prove-it`) |
| --- | --- | --- | --- | --- |
| **Phase 0** | `v0.1.0-infra` | ✅ 已完成并上云 | 多模块骨架、Docker 分层栈、MySQL 14 表 DDL+Seed、3 套 Lua 契约、`starshow-common` 底座、项目级 Skills 与文档治理 | L1 全绿（9/9 编译，6/6 单测，脱敏 0 命中） |
| **Phase 1** | `v0.2.0-chain1`（待打） | ✅ 已完成（含 Phase 1.1 优化全绿） | 链路一：JWT 黑名单、AES+HMAC 实名防黄牛、布隆预约蓄水、有界收敛+SingleFlight 计数、RocketMQ 5.x 延迟提醒、站内通知同事务落账、主办方改期版本联动、我的预约与实名前置引导、双形态 OpenFeign | 全绿（9/9 编译，59/59 常规测试，5/5 真实 Docker 中间件并发/故障/改期/SingleFlight 测试，双形态原包隔离 E2E + 两进程中断恢复 + 哨兵完好 + 脱敏 0 命中；`ISSUE-001`~`010` 全部关闭）。见 [Phase 1.1 实证回执](./issues/ISSUE-007-chain1-acceptance-evidence.md#2026-11-20-链路一深度优化phase-11实证回执)。 |
| **Phase 2** | `v0.3.0-chain2`（待打） | ✅ 已完成（含复验 4 项 P1 闭环） | 链路二：稳定游标浏览、Caffeine L1 + Redis L2 逻辑过期多级缓存、布隆目录防穿透、版本化静态座位图 + 分区 Bitmap MSB 选座、Fencing Token 版本化预热、改期同事务待办恢复、毫秒级开售门禁与存量库无损升级 | 全绿（9/9 编译，75+ 常规测试，12/12 真实 Docker 中间件测试〔链路二 7/7 + 链路一 5/5〕，Phase 1 存量库 `V2` 无损升级实跑 + 双形态原始发行包隔离 E2E `monolith` + `distributed` 全绿，默认库与 Redis DB 0 哨兵 100% 完好，脱敏 0 命中）。见 [链路二规格书与实测记录](./domains/02-show-inventory.md#phase-2-实证验收结果2026-11-20)。 |
| **Phase 3** | `v0.4.0-chain3`（待打） | ✅ 已完成 | 链路三：抢票准入与短时 Token、两段式请求绑定与换设备找回、V1→V4 策略抢票引擎、Lua 原子预扣/连座锁座与门禁校验、RocketMQ 5.x 事务消息防悬挂回查、基因分表三层幂等落单、超时关单任务落账与网关限流 | 全绿（9/9 编译，88/88 常规测试，5/5 链路三真实 Docker 中间件 L2/L3 测试〔V1~V4 四策略 200 并发抢 20 零超卖、50 并发双座连座与走道拦截、COMMIT 丢包回查补单、立碑拦截迟到 Lua `-3`、消费 3x 重投+超时补发、首请重放/换设备找回/A0307/A0308、巡检 vs 消费 `CREATE_DECISION` 竞速〕，双形态原始发行包隔离 E2E `monolith` + `distributed` 全绿，默认库与 Redis DB 0 哨兵 100% 完好，脱敏 0 命中）。见 [链路三规格书与实测记录](./domains/03-order-seckill.md#八phase-3-实证验收结果2026-11-20)。 |
| **Phase 4** | `v0.5.0-chain4`（待打） | 已实现，核心链路与进程恢复通过 | Mock 支付与签名回调、关单、电子票、退款 Saga、库存释放与 T+1 四方对账 | 2026-11-22 新增 7 个进程强杀断点验收，全部恢复且每单只退款、回补各一次。见 [链路四实证结果](./domains/04-payment-reconcile.md#十2026-11-22-进程强杀验收)。 |
| **Phase 5** | `v0.9.0-bench`（待打） | L4 压测通过，观测大屏待验 | 同一 5,001 JWT 池、两种票池、四策略共八轮 | 40,008 次计时请求，200 次幂等重放，零超卖、零重复。万张组 V4 受理 794.7 TPS，P99 161.2 ms，排空 105.310 秒。[实测报告](./benchmarks/BENCHMARK_V1_TO_V4.md) |
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
  - 链路二（Phase 2，分支 `feat/chain2-show-inventory`）全套能力已实证闭环（0 未决缺口）：
    - 存储与契约底座：`t_seat.segment_id` 走道连续段标注、`t_show_task` 幂等任务恢复表、`02_seed.sql` 场次 `10002` 10×10 真实走道布局、Redis `{showId}` 单槽热键（`showReady`/`seatLayout`/`showOpLock`）与 `A0205`/`B0201`(HTTP 503 + `Retry-After`) 错误契约；
    - 节点 0：稳定游标分页 `GET /api/v1/shows`（城市绑定校验）、`GET /api/v1/shows/{showId}` Caffeine L1 (60s) + Redis L2 逻辑过期单锁异步重建 + 30s 空值缓存 + 目录布隆防穿透（100 并发重建严格 == 1，批量不存在 ID SQL == 0 且新增场次不被旧布隆永久误杀）；
    - 节点 1：`GET /api/v1/shows/{showId}/stock` 与 `GET /api/v1/shows/{showId}/zones/{zoneId}/seats`（原始字节 MSB 最高位优先解码，四态 `PREPARING` / 缺键 `503 B0201` / 真实零余量 `SOLD_OUT` / `ENDED` 零误报）；
    - 节点 2 & 3：7 套 `{showId}` 单槽 Lua 脚本实现 Fencing Token 版本化预热、任务崩溃续跑、安全改期（纳入 `PREHEATING` + 原子 `BLOCKED` + 单进程本地 / 分布式 OpenFeign 自动联动链路一重排提醒）、毫秒级准点开售门禁与后台异步 `ON_SALE`/`ENDED` 收敛；固化隔离数据双形态 E2E 脚本 `scripts/verify-chain2-e2e.ps1` 并双形态全绿通过。
  - 链路三（Phase 3，分支 `feat/chain3-order-seckill`）全套能力已实证闭环（0 未决缺口）：
    - 存储与契约底座：`t_order_0..3` 补齐 `request_id`/`request_fingerprint`/`sale_config_version`/`layout_version`/`seckill_strategy` 与 `uk_user_show_request`；新增 `t_order_action` 表（`CREATE_DECISION`/`CLOSE_CHECK`/`RELEASE_STOCK`）；扩展 `ErrorCode`（`A0307`/`A0308`/`B0103` HTTP 503）、`BizException` 结构化 `data` 透传、`RedisKeys` 全 `{showId}` 单槽键、`OrderTimeoutEvent` 与 `ZoneSeatSpecDTO`；
    - 节点 0 & 1：`SeckillRateLimitGlobalFilter`（用户/场次写路径限流 HTTP `429/B0101`）、`POST /api/v1/seckill/token`（绑定 `(userId, showId, saleConfigVersion)` 支持售罄/冲突保留 Token 换档重试）、两段式短 Lua `seckill_req_bind.lua` + `seckill_req_release.lua`、同设备 `clientRequestId` 超时找回与篡改 `A0307`、变价 `A0308`、换设备 `GET /api/v1/orders/current`；
    - 节点 2：V1(`DbOptimistic`) / V2(`ShowLock`) / V3(`TierLock`) / V4(`LuaMq`) 四策略同仓隔离可切换；升级 `seckill_deduct.lua` / `seckill_seat_deduct.lua` / `seckill_rollback.lua` 并新增 `seckill_tx_check.lua` 原子回查立墓碑；自主选座连座走道 `segment_id` 严格校验；
    - 节点 3 & 4：`OrderCreateConsumer` 每票档并发信号量 + 独立短事务预创建 `CREATE_DECISION` 消除 InnoDB Gap Lock 死锁 + 同事务三层幂等落单与 `CLOSE_CHECK` 落账；`OrderTimeoutDispatchScheduler` 可靠投递 `TOPIC_ORDER_TIMEOUT`；`PendingDeductReconcileScheduler`（仅 `ZRANGEBYSCORE`，零 `KEYS`/`SCAN`）；基因分片精准单表查询与稳定游标分页；`starshow-order-exec.jar` 零补丁独立冷启动 + 双形态隔离 E2E 脚本 `scripts/verify-chain3-e2e.ps1` 全绿通过。
  - 真实 Docker Core 栈（MySQL 8.0.39 / Redis 7.4 / RocketMQ 5.3.0 / Nacos v2.4.3）健康运行。
  - 远程仓库推送：已配置仓库级脱敏身份（noreply 邮箱）与独立凭据隔离，推送畅通。

---

## 三、下一阶段执行清单

1. Phase 1（链路一）、Phase 2（链路二）与 Phase 3（链路三）均已全部实证通过，待用户确认后依次合并分支至 `main` 并打 `v0.2.0-chain1` / `v0.3.0-chain2` / `v0.4.0-chain3` Tag。
2. Phase 4 的退款进程级断点已跑通。历史账本重建、真实支付渠道，以及主办方异常单人工处置入口仍待验证。
3. Phase 5 单实例八轮基线已跑通。下一轮加入网关、多实例、热票档倾斜、放票回流以及开票前读流量，定位 V4 在途积压并补齐大屏实证。
4. 对照[万人演唱会产品验收](./reviews/chain-optimization-review.md#八以万人体育馆演唱会验收产品2026-11-22)，先补逐票实名、现场验票和退票规则，再演练完整入场与售后。
5. [ISSUE-012](./issues/ISSUE-012-seckill-request-recovery.md) 在云端真实 MySQL、Redis、RocketMQ 通过聚合态链路三 7 项验收，包括 200 路竞争和两处 JVM 强杀，另有提交索引清理补验通过。分布式发行包 HTTP E2E 待复验。
