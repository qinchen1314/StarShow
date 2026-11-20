---
issue_id: ISSUE-009
title: "[P1][Phase 1] 超窗预约占满补偿批次后，到期提醒无法补发"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, rocketmq-5, scheduling]
---

# [P1][Phase 1] 超窗预约占满补偿批次后，到期提醒无法补发

## 影响与原因

定时任务每轮读取 `remind_status IN (0, 2)` 的最早 100 条预约。超出时间轮窗口的预约进批次后仍保持原状态，下轮又会被选中。当这类记录占满一批，正在等待补发且已入窗的提醒可以持续等不到处理，直到它们也错过开票。[批量查询](../../starshow-user/src/main/java/com/starshow/user/mapper/ReservationMapper.java#L37)、[调度批大小](../../starshow-user/src/main/java/com/starshow/user/mq/ShowRemindRecoveryScheduler.java#L28)、[超窗处理](../../starshow-user/src/main/java/com/starshow/user/mq/ShowRemindProducer.java#L74)

## 实跑证据

在真实 MySQL 和 RocketMQ 上运行分布式发行包，将补偿间隔设为 2 秒。在隔离库按 ID 放入 100 条超窗预约和 1 条已入窗的失败提醒。11 秒后，最后一条仍为状态 `2`。将前 100 条测试记录从候选集合移除，最后一条经真实 Broker 消费变为 `1`。

## 修复与验证记录（2026-11-20）

- **按可处理时间索引过滤 + 超窗让出批次**：
  - 在 `t_reservation` 新增 `next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` 字段与联合索引 `KEY idx_remind_retry (remind_status, next_retry_time, id)`（已同步至 [01_schema.sql](../../docker/mysql/init/01_schema.sql) 与 H2 测试表结构）。
  - [ShowRemindProducer](../../starshow-user/src/main/java/com/starshow/user/mq/ShowRemindProducer.java) 判定预约超出 Broker 时间轮上限（`remind-max-delay-seconds=259200`）时，通过 `casDeferNextRetryTime` 将 `next_retry_time` 推进至该场次精确入窗时刻（`targetDeliverTime - maxDelaySeconds`）；发送失败落 `remind_status = 2` 时通过 `casMarkFailedWithRetry` 叠加退避间隔（`remind-retry-backoff-seconds=30`），不再原地反复抢占下轮批次。
- **游标分页推进 + 积压兜底 + 场次元数据故障隔离**：
  - [ReservationMapper](../../starshow-user/src/main/java/com/starshow/user/mapper/ReservationMapper.java) 的 `selectDueRecoverableReminders(now, afterId, limit)` 仅筛选 `deleted = 0 AND remind_status IN (0, 2) AND next_retry_time <= #{now} AND id > #{afterId} ORDER BY id ASC LIMIT #{limit}`。
  - [ShowRemindRecoveryScheduler](../../starshow-user/src/main/java/com/starshow/user/mq/ShowRemindRecoveryScheduler.java) 每轮按 `afterId` 游标连续拉取多批（默认 `remind-recovery-batch-size=100`、`remind-recovery-max-batches-per-run=20`）消化积压；同轮按 `showId` 缓存元数据查询结果，并对单个 `ShowReadFacade.getShowBasic(showId)` 异常做独立捕获与退避，绝不阻断其他场次补发。
- **实跑验证**：
  - `Chain1LiveMiddlewareVerificationTest#issue009OutOfWindowBacklogAndBrokenShowMetadataDoNotStarveDueReminders`（真实 Docker MySQL 8 + RocketMQ 5.3）：先按小 ID 写入 120 条超窗记录（跨 2 批，`batchSize=50`）、再写入 1 条元数据查询抛异常的故障场次记录，随后写入跨两个不同场次的 2 条已入窗失败记录；运行一轮 `recoverDueReminders(50, 10)` 后，120 条超窗记录 `next_retry_time` 均被推迟至未来且保持 `remind_status=0`，故障场次异常被隔离并写入退避时间，2 条跨场次到期提醒经真实 RocketMQ Broker 消费全部收敛为 `remind_status=1` 且通知各严格 1 次。

## 关闭条件

- [x] 按可处理时间与重试时间查候选记录，保证超窗数据让出本轮批次。写明选取顺序、定时任务积压处理及索引支持（已同步至 [链路一规格](../domains/01-user-reservation.md)）。
- [x] 用真实 MySQL 和 RocketMQ，插入超过一个批次的超窗预约，再插入已入窗的失败记录。断言到期记录在约定轮次内完成补投和消费，超窗记录维持待处理，跨两个场次和连续多批也能推进（`Chain1LiveMiddlewareVerificationTest` 实证通过）。
- [x] 保留 [ISSUE-004](./ISSUE-004-chain1-reminder-recovery.md) 的恢复和消息晚到防重用例。加测单场次元数据暂时查询失败时，其他场次仍可补发（`issue004` + `issue009` 全绿）。
