---
issue_id: ISSUE-014
title: 选座页区分售罄、不可用和释放回流
type: Feature
priority: P1
milestone: Concert A
status: Done
parent_issue: ISSUE-011
blocked_by: []
---

# ISSUE-014 选座页区分售罄、不可用和释放回流

## 业务与范围

买家看得出某票档已无余票、数据暂时故障，或有归还座位可重新选。复用 `ShowStockQueryService`、`SeatMapQueryService` 与 [链路二已验收契约](../domains/02-show-inventory.md)。本单完善体验和兼容用例，正式票池归属与准入分别由 019 和 016 实现。

## 接口与参数

| 路径 | 输入 | 结果与处理 |
| --- | --- | --- |
| `GET /api/v1/shows/{showId}/stock` | 正数 `showId`。 | 复用 `entryState`、每档 `stockState, available` 及 `asOf`。有效零值是 `200/SOLD_OUT`，部分缺键对应 `available=null/STOCK_UNAVAILABLE`，整体缺失 `503/B0201`。 |
| `GET /api/v1/shows/{showId}/zones/{zoneId}/seats` | 场次及现有分区标识，分区必须归该场。 | 保留 `layoutVersion` 和位图对应座位索引。位图缺失或异常走 `503/B0201`，处理 `Retry-After`。 |

选座页先看 `ENDED` 或 `PREPARING`，再看票档状态。仅在销售窗内以新的确认快照从 `SOLD_OUT` 恢复 `OPEN`。不把库存缺失当 0，不补造低于最新布局版本的可选状态。

## 数据与实现顺序

1. 对照 `ShowStockVO`、`SeatMapVO` 和 `SeatMapAndStockQueryTest`，先复用现有字段。补版本或时间字段前写兼容测试。静态详情走原缓存，实时库存和占用始终从 Redis 读。
2. 用 `web-console/seat-stock.html` 演示分区切换、已选座保留、冲突定位、缺键退避和续看余票。快照晚到时按版号和同版 `asOf` 忽略逆序响应。提交仍由订单侧按最新布局校验；旧布局提示重选。
3. 用一场一档两座或一座夹具，先卖光再正常关单回补。记录回补前后 GET 和座位位置变化。也走一次停售后的回补读取，停售仍应正确显示。

## 关闭条件

- [x] L1 验证档位真实 0、部分缺失、全局缺失、准备中和停售。页上有对应文字，失败请求按 `Retry-After` 控制重试。
- [x] L2 两个客户端都能在确认释放后刷新看见可售座，重复释放没有多出座位或票数。旧快照不还原已知新状态。
- [x] 用 `verify-chain2-e2e.ps1` 的隔离方式保存接口样例和页面结果，确保已有分区选座协议保持兼容。

## 2026-11-22 云端验收

- 云端公开基线为 `585ddad`，前端使用 [选座演示页](../../web-console/seat-stock.html)。页面读取同源 API。先依据场次状态和服务端停售时刻，再依据票档及分区快照决定座位是否可选。回流要求两份确认快照均晚于已知售罄，单区缺键独立退避，同版布局校验失败后也可读取更新的座位图并重选。
- `mvn -o -B -T 1 -pl starshow-boot-all -am -Dtest=SeatStockLiveMiddlewareVerificationTest,SeatMapAndStockQueryTest -Dsurefire.failIfNoSpecifiedTests=false -Dstarshow.live.enabled=true -DforkCount=1 test` 通过 7 项，0 失败、0 错误、0 跳过。运行于 Java 21、MySQL 8.0.45 和 Redis 7.0.15。可通过 `scripts/verify-live-tests.ps1 -Chain SeatStock` 复跑同一组云端集成用例。
- 两个测试买家分别锁定 0 号和 1 号座位。卖光后 `available=0`、可选座数为 0。通过正常关单释放 0 号座位，两路回补和一次重放后，两个客户端仍各读到 `available=1`，且仅 0 号可选。停售后回补的库存为 1，接口仍返回 `ENDED`，所有座位不可选。
- `node --test web-console/seat-stock.test.mjs` 通过 7 项。云端 DOM 回放通过 11 项，Firefox 140.17.0 ESR 通过 14 项。两份独立页面复播真实接口样例，覆盖分区切换保留选座、定位冲突座位、按 `Retry-After` 控制请求、缺键与健康区隔离、旧版重选、375 像素视口和停售边界。
- 临时目录保留 `release.json`、`availability.json`、`ended.json`、`dom-results.json`、`browser-results.json`、页面 HTML 和浏览器截图。
- 测试使用独立 MySQL 库、仅授权该库的临时账号和回环绑定的 Redis 实例。Redis DB 0 哨兵保持完好，测试进程、临时库、账号和凭证已回收。原有测试断言未调整。
