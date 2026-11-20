---
issue_id: ISSUE-010
title: "[P1][Phase 1] 为 E2E 脚本隔离测试数据"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, prove-it, data-isolation]
---

# [P1][Phase 1] 为 E2E 脚本隔离测试数据

## 影响与原因

验收文档给出的脚本以项目默认 MySQL、Redis 容器为目标。进入主流程前，它对固定的 `starshow` 库执行三个无条件 `DELETE`，并对 Redis 默认库执行 `FLUSHDB`。在保留开发数据的 Core 栈执行复验，会清空用户、实名、预约，以及默认 Redis 库里的其他业务数据。本轮使用隔离库运行独立验收，源码中的清库流程没有执行。[容器默认值](../../scripts/verify-chain1-e2e.ps1#L16)、[清库操作](../../scripts/verify-chain1-e2e.ps1#L163)

## 修复与验证记录（2026-11-20）

- **严格数据隔离与 Fail-Fast 边界校验**：
  - 全量服务配置（`starshow-boot-all`、`starshow-user`、`starshow-show`、`starshow-gateway`）统一支持 `REDIS_DATABASE`（默认 `0`）与 `MYSQL_DATABASE`（默认 `starshow`）环境变量覆盖。
  - 重写 [scripts/verify-chain1-e2e.ps1](../../scripts/verify-chain1-e2e.ps1)：默认自动生成本轮专属隔离库名 `starshow_e2e_<mode>_<epoch>` 并默认绑定隔离 Redis DB `15`。在任何写入前强校验：拒绝 `TestDatabase=starshow`、拒绝复用已存在的 MySQL 库、拒绝 `TestRedisDb=0`（仅允许 `1..15`）并校验目标 Redis DB `DBSIZE == 0`。
  - 脚本内部将应用进程（`boot-all`/`user`/`show`/`gateway`/`crash-peer`）、健康检查、MySQL 夹具与 Redis 校验全部统一绑定至同一隔离库与隔离 Redis DB；`finally` 保证按进程树清理所有启动的 Java 进程、等待监听端口释放，且仅 `DROP DATABASE` 本轮创建的隔离库并仅 `FLUSHDB` 隔离 Redis DB（绝不触碰 `starshow` 默认库与 Redis DB 0）。
- **6 步实证验证记录**：
  1. 在默认 MySQL `starshow.t_user` 植入哨兵记录（`id=999990001, nickname='ISSUE010_SENTINEL'`），在默认 Redis DB 0 植入哨兵键 `starshow:sentinel:issue010=alive`；
  2. 传入 `-TestDatabase starshow` 与 `-TestRedisDb 0` 均在任何写入前 Fail-Fast 退出（退出码均为 `1`）；
  3. 连续执行 `scripts/verify-chain1-e2e.ps1 -Mode monolith -VerifyMissingSecrets -VerifyCrashRecovery -TestRedisDb 15` 与 `-Mode distributed -VerifyCrashRecovery -TestRedisDb 15`，双形态 100% 通过且每轮自动清理隔离资源；
  4. 执行 `-SimulateMidwayFailure`，脚本返回非零退出码（`1`），并实证本轮隔离库已删除、Redis DB 15 `DBSIZE == 0`、端口 `8000/8001/8002/8012/8090` 全部释放；
  5. 复查默认 MySQL `starshow` 与 Redis DB 0 哨兵数据 100% 完好，随后手动清理验证哨兵。

## 关闭条件

- [x] 使用独立测试库、Redis 测试实例或明确的隔离键空间。每次生成独立测试数据，清理操作只处理本轮创建的资源。
- [x] 校验目标配置，在目标不具备隔离条件时于任何写入前失败。明确 SQL 与 Redis 的健康检查、夹具和应用启动配置指向同一验收环境。
- [x] 在默认库预置哨兵数据，连续运行两种部署形态，断言哨兵数据保持完好。再验证中途失败时退出码非零，且测试进程及测试资源被清理。
