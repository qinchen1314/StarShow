---
issue_id: ISSUE-013
title: 确认中续查和服务端支付倒计时
type: Feature
priority: P1
milestone: Concert A
status: Done
parent_issue: ISSUE-011
blocked_by: [ISSUE-012]
---

# ISSUE-013 确认中续查和服务端支付倒计时

## 业务与范围

买家能离开确认页，再从「我的订单」接着查。只在订单落库且可付款时显示支付入口。复用 012 的恢复服务和现有 `OrderController`、`PaymentApplicationService`，做一页真实接口演示 `web-console/order-confirmation.html`。本单继续使用 Mock 渠道。

## 接口与参数

`POST /api/v1/seckill/orders` 返回 `202/PENDING_CONFIRM` 时包含 `orderNo`，无付款截止。`GET /api/v1/orders/{orderNo}/status?showId=…`、`GET /api/v1/orders/current?showId=…` 和 012 的请求查询补 `serverTime, nextAction, retryAfterMs?, canStartNew`。旧 `pollIntervalMs` 等字段保留。客户端读取本人订单，未落库查询带 `showId`。确认反馈采用以下映射。

| 状态 | 页面行为 |
| --- | --- |
| `PENDING_CONFIRM`、`UNKNOWN` | `CHECK_ORDER`，显示「仍在确认」，待原号的结果。未知不显示已失败。 |
| `FAILED`、`NOT_FOUND` | `canStartNew=true` 才给新购票入口；原请求暂缺证据时遵守 012。 |
| `WAIT_PAY` | 返回已有 `expireTime` 和 `paymentCreateDeadline=expireTime−30s`。`serverTime` 早于后者可 `PAY`，达到边界进入 `CHECK_PAYMENT`。实际支付单的新截止取 `min(建单时刻+14m30s, paymentCreateDeadline)`。 |
| `PAID`、`TICKETED`、`CLOSED`、退款态 | 查看原订单、电子票或退款。终态从服务端取。 |

## 数据与实现顺序

1. 用服务端时钟统一生成 `nextAction`。订单 `WAIT_PAY` 落库后才开启既有 15 分钟关单窗口。已有支付单从支付查询里读实际截止，跨设备不重复建单。
2. 首次提交前按账号、场次保存 `clientRequestId` 和已知 `orderNo`。切换账号隔离清理本地记录。本地不存实名明文或凭证。首次轮询间隔 1、2、4、5 秒，后续 5 秒，取与服务端建议较长者再加少量抖动。30 秒后前台每 15 秒，累计两分钟转手动，后台暂停，返回先查一次。
3. 按原请求号补首响应丢失路径。需要改价、改规则或改座时先提示差异，取 012 的允许条件，再以新请求号提交。

## 关闭条件

- [x] L1 用可控时钟覆盖 30 秒、两分钟和支付边界前后各 1 毫秒。浏览器假时钟验证后台暂停、前台立即续查，MockMvc 验证旧字段仍可读取。
- [x] L3 注入 120 秒落库延迟，按原请求、原订单号及本人同场续查得到同一订单号。等待阶段无支付截止，`WAIT_PAY` 后给出服务端截止，保护窗复查原支付单且同一订单只有 1 个支付单。
- [x] 保存「确认中」「仍在确认」「待支付」页面与接口脱敏样例，先通过 ISSUE-012 云端 7 项故障及并发复跑，再执行 ISSUE-013 的 L3。

## 演示入口与样例

用同一 HTTPS 站点提供 `web-console/` 的静态文件和已有 `/api/v1/` 路由，浏览器打开 `web-console/order-confirmation.html`。聚合态的 `/api/v1/` 指向 `starshow-boot-all`，分布式指向网关。Mock 演示环境需启用支付与模拟扣款。页面登录后可按场次找回原请求，也可从本机近期记录或服务端订单记录打开原单。

### 按买家端规格重构

订单确认页采用 [买家端前端规格](../frontend/FRONTEND_DEVELOPMENT_SPEC.md) 的暖白、深青绿与单列摘要。订单状态、原单号、演出场次、已确认金额和服务端倒计时集中在同一列。进入「查看我的订单」停止当前页定时查询，并可从「本机续查」回到原场次。同账号刷新后可按原请求续查，换设备后按本人场次查询。本机仅保存账号隔离的续查信息。

本次 Edge 复跑使用隔离模拟接口。覆盖 202 受理、首响应丢失还原、30 秒降频、两分钟停止定时查询、后台切回、保护窗只查询、原支付单唯一、退出清理，以及 320、375、390、768、1024、1440 px 的无横向溢出与 44 px 主操作。另确认服务端分值显示为人民币金额、打开与离开订单时焦点回到对应标题，以及减少动态效果设置生效。 `node --test web-console/order-confirmation.test.mjs web-console/seat-stock.test.mjs` 通过 9 项。后端 HTTP 契约与云端验收条件沿用本 Issue。

本次离线 Maven 全仓回归 9 个模块成功，95 项通过、32 项按配置跳过。保留原有测试断言，并核查本次改动的敏感数据与本机路径。通用落地页检查仍提示整屏首屏和装饰场景等要求，订单页按项目规格使用单列交易布局。

浏览器使用独立的回环测试接口生成以下脱敏材料。

- [确认中](./evidence/issue-013/confirming.png)
- [仍在确认](./evidence/issue-013/still-confirming.png)
- [待支付](./evidence/issue-013/awaiting-payment.png)
- [受理、续查和支付保护窗响应样例](./evidence/issue-013/responses.json)

## 2026-11-22 实证回执

- 改为云端验收前，隔离构建目录实跑 `mvn -o -B test`，9 个模块通过，95 个测试通过，29 个显式启用的中间件测试跳过。三个续查路由的固定时钟在截止前 1 毫秒返回 `PAY`，在截止时刻及后 1 毫秒返回 `CHECK_PAYMENT`。
- `node --test web-console/order-confirmation.test.mjs` 通过 2 项。Edge 假时钟串行验证 HTTP 202、首响应丢失、按原请求找回、30 秒降频、两分钟转手动、切回前台立即续查、支付保护窗和账号切换。375 px 窗口无横向溢出。
- 云端 `mvn -o -B -T 1 -pl starshow-boot-all -am test` 通过 86 项，29 项真实中间件用例按配置跳过。全仓云端离线构建在网关模块缺两个缓存依赖，改按受影响模块实跑。
- 云端串行复跑 ISSUE-012 的 `Chain3LiveMiddlewareVerificationTest`，7 项全部通过。首次复跑达到容器线程上限，提升限制后保留 200 并发和故障断言重跑。回收测试库与 Redis 测试 DB，Redis DB 0 的键数保持一致。
- L3 在云端按[总测试说明](../TESTING.md)运行 `Chain4LiveMiddlewareVerificationTest#resumesOriginalOrderAcross120SecondDeliveryDelayAndRespectsPaymentGuard`，1 项通过，Maven 阶段耗时 143 秒。Redis 预扣后实际等待 120 秒，再把事件交给正式消费逻辑写入隔离 MySQL。原单重投一次仍只扣一份库存，关单窗口从落库时刻起算 15 分钟，30 秒保护窗返回 `CHECK_PAYMENT`。已有支付单复用原号，无支付单的订单返回 `A0405` 且没有新增支付流水。
- 首次延迟用例已走通落库，支付断言碰到渠道期限较早的一秒。修正夹具时机后复跑，保留付款截止的精确相等断言。`git diff --check` 通过，原有测试断言零删除，凭据与本机路径模式扫描零命中，`.env` 未跟踪。测试数据库、账号、容器和上传目录均已回收，测试 Redis DB 清空，Redis DB 0 键数保持一致，宿主机 OOM 计数增量为 0。
