# StarShow 长周期开发进度账本 (PROGRESS.md)

> **跨会话接续第一入口**：每次开启新开发会话，先读本页即可掌握当前进度、已验证能力、挂起事项与下一步指令。

---

## 一、里程碑总览

| 阶段 | Git Tag | 状态 | 核心目标 | 实证验收状态 (`prove-it`) |
| --- | --- | --- | --- | --- |
| **Phase 0** | `v0.1.0-infra` | ✅ 已完成并上云 | 多模块骨架、Docker 分层栈、MySQL 14 表 DDL+Seed、3 套 Lua 契约、`starshow-common` 底座、项目级 Skills 与文档治理 | L1 全绿（9/9 编译，6/6 单测，脱敏 0 命中） |
| **Phase 1** | `v0.2.0-chain1` | 🔜 待启动 | 链路一：JWT 黑名单、AES+HMAC 实名防黄牛、布隆预约蓄水、RocketMQ 5.x 延迟提醒 | 待验收 |
| **Phase 2** | `v0.3.0-chain2` | ⬜ 未开始 | 链路二：Caffeine L1 + Redis L2 逻辑过期、分区 Bitmap 座位图、开票 Preheat 预热 | 待验收 |
| **Phase 3** | `v0.4.0-chain3` | ⬜ 未开始 | 链路三：V1→V4 策略抢票引擎、Lua 预扣/选座、RocketMQ 事务消息防悬挂回查、基因分表 | 待验收 |
| **Phase 4** | `v0.5.0-chain4` | ⬜ 未开始 | 链路四：Mock 支付故障注入、15min 关单竞速、出票履约、退款 Saga、悬挂+T+1 四方对账 | 待验收 |
| **Phase 5** | `v0.9.0-bench` | ⬜ 未开始 | 5,000+ Token 池真实 V1→V4 压测（含 V4 E2E 排空耗时）与 Grafana 观测报告 | 待验收 |
| **Phase 6** | `v1.0.0-release` | ⬜ 未开始 | 零构建单页演示台 (`web-console/`)、完整开源 README 与 GitHub Actions 全绿发布 | 待验收 |

---

## 二、当前环境与已知挂起项

- **已验证通过**：
  - 本机编译环境：JDK 25 + `--release 21` + Lombok `1.18.42` + Spring Boot `3.3.5`（实测 9 模块全绿）
  - 远程仓库推送：已配置仓库级脱敏身份（noreply 邮箱）与独立凭据隔离，推送畅通
  - Core / Ops Docker Compose 配置语法校验通过
- **待联调前置条件**：
  - 本地 **Docker Desktop 尚未启动**：进入 Phase 1 联调前需先启动 Docker Desktop，执行 `docker compose -f docker/docker-compose.yml up -d` 拉起 MySQL 8 / Redis 7 / RocketMQ 5.3 / Nacos，并验证 `01_schema.sql` & `02_seed.sql` 自动初始化成功。

---

## 三、下一阶段（Phase 1）执行清单

切分支 `feat/chain1-user-reservation`，对照 [domains/01-user-reservation.md](./domains/01-user-reservation.md) 按顺序落地：
1. `starshow-common` Web 支撑：`TraceIdFilter`、`GlobalExceptionHandler`
2. `starshow-user`：`JwtTokenService` + Redis 黑名单、注册/登录/登出接口、AES-GCM + HMAC-SHA256 实名认证接口、`UserFacade` 本地实现
3. `starshow-user` 预约蓄水：Redisson 布隆过滤器 + `t_reservation` 唯一索引防重 + Redis 实时预约计数 + RocketMQ 5.x `setDeliverTimeMs` 延迟提醒生产与消费
4. `starshow-gateway`：JWT 鉴权与黑名单全局过滤器
5. 执行 `prove-it` 验收（L1 单测 + 集成/MockMvc 实跑 + 重复身份证/重复预约拦截实证）→ 合并 `main` → 打 `v0.2.0-chain1` Tag → 更新本账本
