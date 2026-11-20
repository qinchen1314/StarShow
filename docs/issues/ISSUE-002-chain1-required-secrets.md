---
issue_id: ISSUE-002
title: "[P0][Phase 1] 缺失密钥配置时拒绝启动"
type: Bug
priority: P0
milestone: Phase 1 (v0.2.0-chain1)
status: Closed (Verified)
parent_issue: ISSUE-001
labels: [phase-1, chain-1-reservation, security]
---

# [P0][Phase 1] 缺失密钥配置时拒绝启动

## 现象与证据

网关、用户服务、演出服务和聚合应用的运行配置会在缺少环境变量时使用仓库公开的默认凭据。公共安全属性类也保留 JWT、内部鉴权、AES 和 HMAC pepper 默认值。Servlet 过滤器将匹配内部密钥的身份头视为可信上下文，优先于 JWT 和 Redis 黑名单检查。开发默认值因此会跨越实际的身份信任边界，并削弱使用默认 AES 密钥或 pepper 写入的实名数据保护。依据为源码审查，未发现真实生产凭据。代码位置见 [公共安全属性](../../starshow-common/src/main/java/com/starshow/common/config/StarShowSecurityProperties.java)、[Servlet 信任分支](../../starshow-common/src/main/java/com/starshow/common/web/AuthContextServletFilter.java)、[聚合运行配置](../../starshow-boot-all/src/main/resources/application.yml)、[网关运行配置](../../starshow-gateway/src/main/resources/application.yml)、[用户运行配置](../../starshow-user/src/main/resources/application-user.yml)、[演出运行配置](../../starshow-show/src/main/resources/application-show.yml)。

## 规格与影响

[AGENTS.md 的安全红线](../../AGENTS.md)要求通过环境变量注入密码与密钥。两种运行形态都依赖这条边界，公开默认值可用于实际鉴权或数据加密时，Phase 1 发布阻塞。

## 修复与验收

- [x] 按组件校验所需凭据（`SecuritySecretValidator` + `GatewaySecurityValidator` / `ShowSecurityValidator` / `UserSecurityValidator`）。缺失、空白或使用公开占位值时启动失败，错误只包含配置项名称。应用与 `docker/docker-compose.yml` 均要求通过环境变量注入凭据。
- [x] JWT 密钥至少 32 字节，实名 AES-256 密钥恰为 32 字节，内部共享密钥和 HMAC pepper 分别注入（`>= 16` 字节）。占位值仅保留在 `.env.example`，测试夹具显式提供独立测试值。
- [x] 回归覆盖各入口缺失配置的启动拒绝（`SecuritySecretValidatorTest`、`GatewayAutoConfigurationIsolationTest`、`ShowServiceStartupAndSecurityTest`、`UserSecurityValidatorTest` + `scripts/verify-chain1-e2e.ps1 -VerifyMissingSecrets` 4/4 原始发行包实测拒绝 + `docker compose config` 缺密退出码 1），以及注入独立凭据后双形态完整登录、登出、黑名单拦截与伪造头拒绝。
- [x] 同步链路一规格、配置示例和进度账本。
