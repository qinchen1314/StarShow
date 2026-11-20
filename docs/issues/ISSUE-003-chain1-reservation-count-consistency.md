---
issue_id: ISSUE-003
title: "[P1][Phase 1] 修复预约计数冷启动并发漂移"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, redis, consistency]
---

# [P1][Phase 1] 修复预约计数冷启动并发漂移

## 现象与证据

预约写入 MySQL 后才更新 Redis。计数键缺失时存在两个可由源码推导的交错顺序。

1. 首个 POST 的 `INCR` 返回 `1`，并读到 DB 总数 `N`。另一个 POST 随后提交并完成 `INCR`，首个 POST 再用普通 `SET` 写入旧快照 `N`，最终漏计一条。
2. POST 已提交 DB、尚未自增时，GET 将含这笔预约的 DB 总数通过 `SET NX` 回填，POST 随后 `INCR` 同一笔预约，最终多计一条。

## 修复与验收

- [x] 统一 GET 回填与 POST 计数写入策略（`ReservationCountStore`：单 Key Hash `starshow:rsv:count:<showId>` `{epoch,count}` + 写前捕获 `preInsertEpoch` + 写后单 Key Lua `CAS-INCR` + `RLock` 两阶段 Fence 栅栏重建 + `dirtyShows` 故障自愈恢复），确保从 MySQL 线性一致收敛且每条成功预约精确计一次。
- [x] 用真实 Docker MySQL 8 和 Redis 7 运行 `Chain1LiveMiddlewareVerificationTest.issue003_*`，利用 `CyclicBarrier` / `CountDownLatch` 同步屏障覆盖交错 1（20 并发冷启动 -> 20）与交错 2（POST 落库后卡屏障被 GET 抢先回填 -> 21，零多计），断言 Redis Hash、GET 返回与 MySQL 有效预约行数完全相等。
- [x] 覆盖有历史数据时删除计数键、Redis 暂不可用触发 `dirty` 后自动恢复收敛（22）、同一用户 16 线程并发重复预约（1 成功、15 次 `A0106`、DB 仅增 1 行、计数收敛 23）。
- [x] 同步链路一规格、计数接口契约与进度账本。
