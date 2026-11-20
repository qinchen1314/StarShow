---
issue_id: ISSUE-008
title: "[P1][Phase 1] 预约落库后进程中断导致计数持续少计"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, redis, consistency]
---

# [P1][Phase 1] 预约落库后进程中断导致计数持续少计

## 影响与原因

预约已经写入 MySQL 后，进程仍需完成 Redis 更新。如果它在两步之间退出，Redis 里有效的旧计数继续被公开接口读取。Redis 写入故障走进程内的 `dirtyShows` 降级。同场次流量切换到其他实例，或该实例重启时，故障标记也无法随预约记录恢复。后续成功的预约会在旧计数上递增，主办方看到的预约量仍然偏低。[预约写入顺序](../../starshow-user/src/main/java/com/starshow/user/service/ReservationService.java#L84)、[计数更新和降级](../../starshow-user/src/main/java/com/starshow/user/service/ReservationCountStore.java#L102)、[计数读取](../../starshow-user/src/main/java/com/starshow/user/service/ReservationCountStore.java#L125)

另有一个恢复期竞态。重建锁租约固定为 10 秒，发布快照的 Lua 无条件覆盖计数与代际。原重建者超过租约后仍能发布旧快照，覆盖后续持锁者已经发布的结果。这一项来自代码审查。[重建锁与快照发布](../../starshow-user/src/main/java/com/starshow/user/service/ReservationCountStore.java#L149)

## 实跑证据

在隔离库启动原始用户服务发行包，用 GET 将空场次计数预热为 `0`。随后只在真实 MySQL 提交一条预约，构造 DB 已提交且 Redis 更新尚未发生时的存储状态。立刻核对得到 `MySQL=1 / Redis=0 / GET=0`，经过至少 11 秒及多轮提醒补偿后再次请求仍为 `0`。本次通过夹具注入存储状态，关闭时需加两进程中断测试。

## 修复与验证记录（2026-11-20）

- **有界回源收敛**：在 [ReservationCountStore](../../starshow-user/src/main/java/com/starshow/user/service/ReservationCountStore.java) 中将单 Key Hash `starshow:rsv:count:<showId>` 设为有界生存时间（默认 `starshow.reservation.count-cache-ttl-seconds=30`），且 `CAS_INCR_SCRIPT` 自增时不续期 TTL。任意实例在 MySQL 提交后、Redis 更新前硬中断或切换流量，最迟在 `count-cache-ttl-seconds` 内自动触发回源重建收敛；同时预约成功状态始终与 Redis 短时故障解耦。
- **Fencing Token 阻断陈旧快照覆盖**：重建前通过 `FENCE_SCRIPT` 将 `epoch` 原子置为 `REBUILDING:<fencingToken>`，提交快照改用 `FENCED_COMMIT_SNAPSHOT_SCRIPT` 校验 `HGET epoch == "REBUILDING:<fencingToken>"` 通过后才允许发布新代际并设置 TTL；锁租约超期的旧重建者恢复提交时返回 `0` 被原子拒绝。
- **实跑验证**：
  - `Chain1LiveMiddlewareVerificationTest#issue008FencingTokenBlocksStaleRebuilderAndBoundedTtlRecoversAfterCrash`（真实 Docker MySQL 8 + Redis 7）：先将重建者 A 挂起超过锁租约（1s），待 B 完成含新增预约的新快照后放行 A，实证 A 无法覆盖 B；再注入 DB 已提交但 Redis 未更新状态，等待 2s TTL 窗口后 `GET /count` 自动收敛且与 MySQL 一致。
  - `scripts/verify-chain1-e2e.ps1 -Mode monolith -VerifyCrashRecovery` 与 `-Mode distributed -VerifyCrashRecovery`（真实两进程 `boot-all/user` + 独立 peer 进程 `:8012`）：在 DB 提交后直接强杀写入者进程，切换流量至 peer 节点于 4s 内（配置 `TTL=3s`）实证 `GET /count == 1`，重启原写入者进程后同用户重试预约返回 `A0106` 且 `MySQL == Redis == GET == 1`。

## 关闭条件

- [x] 让已提交预约对应的计数更新可持久恢复，或让计数具备有界回源校准。明确对外一致性口径与恢复时限（见 [链路一规格](../domains/01-user-reservation.md) 与 [接口契约](../contracts/API_CONTRACT.md)，默认 30 秒有界最终一致）。预约结果与 Redis 暂时不可用解耦。
- [x] 阻止失去重建权的实例发布旧快照。测试先挂起重建者 A，等待锁租约结束，B 完成较新重建后恢复 A，断言 A 无法覆盖 B（`Chain1LiveMiddlewareVerificationTest` 实证通过）。
- [x] 用真实 MySQL、Redis 和两个独立进程复跑。在 DB 提交与缓存更新之间中断写入者，然后切换流量并重启进程。在约定时限内断言 DB、Redis 和 GET 一致，重试预约仍只落一条且只计一次（`monolith` 与 `distributed` 双形态实证通过）。
- [x] 保留 [ISSUE-003](./ISSUE-003-chain1-reservation-count-consistency.md) 已通过的冷启动、并发防重和实例内故障测试（`issue003ReservationCountConcurrencyAndFaultRecovery` 全绿）。
