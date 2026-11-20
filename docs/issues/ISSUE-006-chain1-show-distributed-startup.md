---
issue_id: ISSUE-006
title: "[P1][Phase 1] 修复演出服务发行包独立启动失败"
type: Bug
priority: P1
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, distributed, packaging]
---

# [P1][Phase 1] 修复演出服务发行包独立启动失败

## 修复与验收

- [x] 将 `ConstraintViolationException` 处理从 `GlobalExceptionHandler` 拆分至按 `@ConditionalOnClass` 条件装配的 `ValidationExceptionHandler`，并在 `starshow-show/pom.xml` 引入 `spring-boot-starter-validation`；`GatewayAutoConfigurationIsolationTest` 持续实证 WebFlux 网关零 Servlet 依赖污染。
- [x] 从干净构建生成的原始 `starshow-show/target/starshow-show-0.1.0-SNAPSHOT-exec.jar --spring.profiles.active=distributed` 独立启动（无需任何临时类路径补丁），实证 `/actuator/health` 200 及 `/internal/shows/10003/basic` 无密钥 401、带内部密钥 200 返回 `10003`；新增自动化测试 `ShowServiceStartupAndSecurityTest`。
- [x] 通过 `scripts/verify-chain1-e2e.ps1 -Mode distributed` 驱动独立网关（8000）、用户（8001）与演出（8002）原始发行包对真实 Docker Core 栈走完预约与 5 秒延迟提醒闭环并同步进度账本。
