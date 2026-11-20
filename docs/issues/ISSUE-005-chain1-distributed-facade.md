---
issue_id: ISSUE-005
title: "[P1][Phase 1] 分布式演出 Facade 接入 OpenFeign"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, architecture, distributed]
---

# [P1][Phase 1] 分布式演出 Facade 接入 OpenFeign

## 修复与验收

- [x] 按 [AGENTS.md](../../AGENTS.md) 架构红线接入 Spring Cloud OpenFeign（`DistributedFeignEnableConfig` + `ShowServiceFeignClient` + `ShowServiceFeignClientConfig` + `FeignShowReadFacadeAdapter`，移除手工 `RestClient`），仅在 `@Profile("distributed")` 下激活，统一透传 `X-Internal-Gateway-Secret` 与 `X-Trace-Id`，显式配置连接（`2000ms`）与读取（`3000ms`）超时。
- [x] `Chain1MonolithIntegrationTest` 断言 `monolith` 下只注入本地 `ShowReadFacadeImpl` 且零 `ShowServiceFeignClient` Bean；`DistributedFeignClientContractTest` 断言拦截器头透传与超时配置；`scripts/verify-chain1-e2e.ps1 -Mode distributed` 实跑独立网关（8000）、用户（8001，OpenFeign）与演出（8002）三进程真实跨进程预约闭环。
- [x] `StarShowUserApplication` / `StarShowShowApplication` 自动追加 `user`/`show` profile 加载独立配置，并在 [01-user-reservation.md](../domains/01-user-reservation.md) 补全可直接执行的双形态冷启动命令与环境变量清单。
