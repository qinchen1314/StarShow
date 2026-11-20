---
issue_id: ISSUE-004
title: "[P1][Phase 1] 完成延迟提醒补发与状态收敛"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, rocketmq-5, consistency]
---

# [P1][Phase 1] 完成延迟提醒补发与状态收敛

## 修复与验收

- [x] 补齐可重试的入窗投递与失败恢复调度器（`ShowRemindRecoveryScheduler`，`@Scheduled` 扫描 `remind_status IN (0, 2)`）。定义持久化四态：`0` 待投递/超窗、`3` Broker 已排期、`2` 发送失败/超时待重试、`1` 终态已送达，完全兼容历史 `0`/`2` 记录。
- [x] 全部状态变更采用数据库级单向 CAS（`casMarkScheduled`: `0/2->3`，`casMarkFailed`: `0/2->2`，`casMarkReminded`: `0/2/3->1`），确认已送达终态 `1` 禁止任何路径回退；抽象 `ShowRemindNotifier`，补投与原消息双到时仅首次 CAS 成功并触发一次模拟通知。
- [x] 在真实 Docker MySQL 8 + RocketMQ 5.3 上实跑 `Chain1LiveMiddlewareVerificationTest.issue004_*` 全部三组故障场景（超 72h 入窗补投、Broker 故障后恢复补投、Broker 已收但发送端超时 + 补投双到 + 重放），均验证预约成功、唯一记录、终态 `1` 绝不回退且通知次数严格为 `1`。
- [x] 同步链路一规格中的重试、状态转换、测试和进度账本。
