# 板块 04：Mock 支付沙箱、故障注入、退款 Saga 与四方对账（链路四）

- **映射代码模块**：`starshow-payment`（联动 `starshow-order`）
- **计划阶段**：Phase 4（`v0.5.0-chain4`）
- **状态**：⬜ 未开始

## 1. 数据与中间件归属
- **MySQL 表**：`t_payment_order`、`t_refund_order`、`t_reconciliation_diff`
- **Redis Key**：`starshow:lock:order:<orderNo>`、`starshow:deduct_pending:{showId}`（巡检扫描）
- **RocketMQ Topic**：`TOPIC_REFUND_EVENT`（按 `orderNo` 顺序投递，消费组 `cg-refund-saga`）

## 2. 核心设计要点
1. **Mock 支付故障注入沙箱**：内置验签与 5 种演练模式：
   - `NONE`：正常支付并回调
   - `DUPLICATE_3X`：连续投递多次相同回调（验证幂等与只出 1 张票）
   - `DROP_CALLBACK`：扣款成功但丢弃回调（验证 15min 关单前主动查单补票）
   - `DELAY_20S`：延迟回调
   - `RACE_WITH_CLOSE`：人为制造关单后滞后回调（验证绝不复活 `CLOSED` 并自动转原路退款）
2. **错峰过期与状态机底线**：支付单默认 `14m30s` 过期，业务关单 `15m`；订单一旦 CAS 变 `CLOSED` 永不复活。
3. **退款 Saga 状态机**：`INIT -> LOCK_TICKET -> REFUND_PAY -> ROLLBACK_STOCK -> DONE`，持久化每一步状态，支持失败重试与定时续跑。
4. **双重对账底网**：
   - 每 5 分钟准实时巡检：`ZRANGEBYSCORE deduct_pending` 清理悬挂预扣 + 扫超 16min 漏网 `WAIT_PAY` 订单；
   - T+1 四方全量对账：比对「Mock 渠道流水 vs `t_payment_order` vs `t_order` vs `t_ticket`」，差异分类写入 `t_reconciliation_diff`。

## 3. Prove-It 验收红线（Phase 4 完成标准，L3 六大魔鬼剧本）
- 剧本 1：连续发 5 次支付回调 → 只生成 1 笔有效支付流水、只出 1 张电子票；
- 剧本 2：模拟丢回调 → 超时关单任务主动查单发现已付，自动补单出票不误关；
- 剧本 3：模拟关单后滞后回调 → 订单保持 `CLOSED` 不复活，支付单标 `ABNORMAL_PAID`，自动生成退款单并完成原路退款；
- 剧本 4：正常退款中断某一步后重跑 Saga → 最终到达 `DONE`，库存与座位仅回补一次；
- 剧本 5：人为制造 1 笔长/短款差异 → 四方对账任务精准捕获并落盘 `t_reconciliation_diff`。
