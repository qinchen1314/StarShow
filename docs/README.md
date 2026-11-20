# StarShow 文档治理中心（Single Source of Truth）

> 目标：支撑长链路、跨多轮迭代与多模块协同开发，确保**代码、契约、架构决策与进度记忆始终对齐，不漂移、不失忆**。
>
> **O(1) 机器/人工路由总字典**：[docs/INDEX.yaml](./INDEX.yaml)（按关键词 1 秒定位 `read_docs`、`read_refs`、`code_anchors`、`sync_docs` 与 `prove_it_level`）。

---

## 一、三层治理分工（各司其职，严禁重复拷贝）

| 层级 | 位置 | 定位 | 更新频率 |
| --- | --- | --- | --- |
| **规则速查层** | `AGENTS.md` | 仓库最高红线一页纸摘要 | 仅架构大变时 |
| **执行宪法层** | `.agents/skills/starshow/` & `prove-it/` | AI 开发流程、编码正反例、测试防弱化验收门禁 | 仅工程规范变更时 |
| **项目事实层** | `docs/`（本目录） | **唯一业务事实库**：模块设计、接口契约、ADR 决策、进度账本、压测证据 | 随每个 Phase / 功能同步更新 |

---

## 二、代码模块 ↔ 文档 1:1 映射矩阵

改动左侧任意代码模块时，**必须同步核对并更新**右侧对应文档：

| 代码模块 | 所属链路 | 对应治理文档 | 关联契约 / 脚本 |
| --- | --- | --- | --- |
| `starshow-common` / `starshow-api` | 全局底座 | [ARCHITECTURE.md](./ARCHITECTURE.md)、[adr/README.md](./adr/README.md) | `01_schema.sql` |
| `starshow-gateway` + `starshow-user` | 链路一（鉴权与预约） | [domains/01-user-reservation.md](./domains/01-user-reservation.md) | [contracts/API_CONTRACT.md](./contracts/API_CONTRACT.md) |
| `starshow-show` | 链路二（缓存与选座） | [domains/02-show-inventory.md](./domains/02-show-inventory.md) | `02_seed.sql` |
| `starshow-order` | 链路三+四（抢票与履约） | [domains/03-order-seckill.md](./domains/03-order-seckill.md) | `starshow-order/.../lua/*.lua` |
| `starshow-payment` | 链路四（支付与对账） | [domains/04-payment-reconcile.md](./domains/04-payment-reconcile.md) | [contracts/API_CONTRACT.md](./contracts/API_CONTRACT.md) |
| `load-test/`（Phase 5） | 性能演进实验 | [benchmarks/BENCHMARK_V1_TO_V4.md](./benchmarks/BENCHMARK_V1_TO_V4.md) | `docker-compose.ops.yml` |
| 全局里程碑与跨会话接续 | 全周期 | [PROGRESS.md](./PROGRESS.md) | Git Tags (`v0.1.0`~`v1.0.0`) |

---

## 三、文档治理铁律（防僵尸文档）

1. **改码必同步（Docs-as-Code）**：凡涉及接口路径/字段变化、表结构变更、状态机变更、Lua 脚本变更，必须在同一 Commit 或同一 PR 内同步更新映射文档，并由 `prove-it` 验收门禁抽查。
2. **新会话接续第一入口**：开启新窗口或隔天继续开发时，先读 [PROGRESS.md](./PROGRESS.md) 再读目标板块的 `docs/domains/0x-*.md`，10 秒恢复完整上下文。
3. **只写事实与证据，不写空话**：文档中只保留已拍板契约、实测数据、明确 TODO 与已知限制；严禁编造未测指标，严禁混入真实密码、个人路径或个人邮箱。

## 四、验收 Issue 与回执

里程碑复核发现的缺口归档在 [docs/issues](./issues/)。进度与发布状态从 [PROGRESS.md](./PROGRESS.md) 进入。每条 Issue 记录 P 级、源码或实跑证据、影响与关闭断言，并关联原 Epic。链路一从 [ISSUE-001](./issues/ISSUE-001-chain1-user-reservation.md) 进入，最新实跑记录见 [终版复验回执](./issues/ISSUE-007-chain1-acceptance-evidence.md#2026-11-20-终版复验回执issue-008--issue-010-闭环)。

[四条票务链路优化评审](./reviews/chain-optimization-review.md)记录预约、开售、下单、履约的交接建议，供各阶段的规格评审使用。
