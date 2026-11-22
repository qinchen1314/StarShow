---
issue_id: ISSUE-012
title: V4 首响应丢失后找回同一抢票请求
type: Feature
priority: P0
milestone: Concert A
status: In Progress
parent_issue: ISSUE-011
blocked_by: []
---

# ISSUE-012 V4 首响应丢失后找回同一抢票请求

## 业务与范围

买家提交后断网、杀 App 或换手机，在订单页能找回尚未扣减或尚未落库的原请求。原参数重放仍使用同一订单号。先读 `SeckillOrderService`、`OrderQueryService`、`seckill_req_bind.lua` 和 `PendingDeductReconcileScheduler`。补齐现有绑定后、预扣前的恢复，并保留 V1～V3 原行为。

## 接口与参数

| 入口 | 入参和权限 | 响应与重试 |
| --- | --- | --- |
| `GET /api/v1/seckill/requests/{clientRequestId}?showId=…` | 本人 JWT。`showId` 为正数 ID，`clientRequestId` 复用现有 8～36 字符校验。 | `status, orderNo?, canStartNew, retryAfterMs?`。证据缺失或暂不可用时以 HTTP 200 返回 `UNKNOWN`；已证实终止返回 `FAILED`。 |
| `GET /api/v1/orders/current?showId=…` | 本人 JWT 和正数 `showId`。 | 先查本人有效 DB 单，再查在途，再查本人 Redis 已购单号。只有各必需来源查询成功且无活动证据才返回 `NOT_FOUND`。保留 `hasOrder`。 |
| `POST /api/v1/seckill/orders` | 同一 `clientRequestId` 重放原业务参数。先验证本人身份和请求指纹。 | 同请求不同业务参数返回 `A0307`；当前请求未决时，新请求号返回原办理结果。已受理或已落库的同号重放为只读恢复。在绑定阶段续租并继续执行时，应验证当前抢票凭证；开候场的场次还须按 016 验准入资格。 |

错误或未授权查询不泄露其他账号的单号。`canStartNew` 只在确认无活动请求，或失败且已无本请求占用时为真。

## 数据与实现顺序

1. 绑定请求时原子写 `starshow:seckill:current:{showId}:<userId>` 与 `starshow:seckill:submit_pending:{showId}` ZSET，后者以租约到期时刻为分值。沿用原请求映射并保存 `orderNo`、指纹、租约版本。写入、续租与释放按订单号及版本比较。索引保留期覆盖巡检恢复，未决记录不能仅靠 TTL 消失，已结记录按受控保留期清理。
2. 先接上按请求号和按本人同场的读路径。DB 查询仍命中本人分片。该请求对应的 DB 终态优先于短期 Redis 状态。
3. 有界巡检遍历已知可售和待恢复场次。存在已创建订单时只清匹配索引，有 `deduct_log` 时交给原预扣巡检。两者无证据时先做同槽的租约检查与墓碑裁决，再按 `CREATE_DECISION` 锁内结果终止。Broker 回查与迟到 Lua 走同一约束。确认归还后才放开新请求。证据不可用留原索引并重试。

## 关闭条件

- [x] L1 本人按请求号和同场账号查得同一订单号，越权查不到订单号。换参数拒绝且无扣减。临时不可用返回 `UNKNOWN`、禁止起新单。
- [x] L2 至少 200 个并发重放和新请求竞争，夹具中最多一条有效单。钱、票库存和本账号占用一致，新请求不得覆盖旧索引。
- [x] L3 在绑定后和预扣后分别停止工作进程，验证原号续领、迟到 Lua 被墓碑挡住、DB 已存在仅清索引、旧索引不能删新索引。聚合态使用与 `verify-chain3-e2e.ps1` 相同的独立 MySQL 库和非 0 Redis DB 隔离方式。

## 2026-11-22 实证回执

- L1 实跑 `mvn -o -B test`，9 个模块构建通过，92 个测试通过，27 个需要显式启用的中间件测试跳过。两个 GET 路由经 MockMvc 校验预扣前均返回原单号，且订单号为 JSON 字符串。覆盖其他账号不可见、并发起新请求返回原单号和数据源故障不能起新单。
- 独立 Redis 8.0.5 实跑 200 次竞争调用。同号重放和新请求各 100 次，全部关联原单号，只有 1 次续租获胜、1 次库存预扣。旧租约返回 `-7`，墓碑后的迟到锁座返回 `-3`，延迟清理未覆盖新索引。两个临时写进程分别在绑定、预扣后退出。
- Maven 与测试 JVM 串行，堆上限分别为 512 MiB 和 384 MiB。仅启动一个回环地址 Redis，`maxmemory=64 MiB`，实测数据内存峰值约 1.32 MiB，结束后确认进程退出且端口关闭。
- 检查 `git diff --check`、测试变更及新增文件，现有测试断言零删除。新增行的密钥、个人邮箱、本机路径和代理模式扫描零命中，`.env` 未跟踪。链路三规格、API 契约和进度账本已同步。
- 本地实测时 Docker 引擎未运行。MySQL、RocketMQ 与 Java 工作进程强杀已在同日补做云端验证，结果见下节。

## 2026-11-22 云端 L1～L3 验收

- 本地 `mvn -o -B -pl starshow-order -am test` 通过 31 项。Java 21 测试源码编译通过，新增用例保留原有断言和等待门限。
- 云端以 Docker 运行 RocketMQ 5.3.0 NameServer、Broker 和 Java 21 / Maven 测试容器，复用 MySQL 8.0、Redis 7.0.15。临时业务库只授予当次账号权限，Redis 使用 DB 12。RocketMQ 的宿主机端口只绑定回环地址。
- 云端实跑 `mvn -o -B -T 1 -pl starshow-boot-all -am -Dtest=Chain3LiveMiddlewareVerificationTest -Dsurefire.failIfNoSpecifiedTests=false -Dstarshow.live.enabled=true -Dstarshow.live.crash.enabled=true -DforkCount=1 test`，7 项通过，无失败、错误或跳过。首次自动创建 Topic 后，其首笔消息在 15 秒断言之后才被消费。预置 `TOPIC_ORDER_CREATE` 和 `TOPIC_ORDER_TIMEOUT` 后按原断言复跑全绿。
- 200 个并发调用中，同号重放与新请求各 100 个。只有 1 个续租获胜，原号落 1 单。夹具起始库存 2，MySQL 和 Redis 均剩 1，订单金额 88,000 分，支付流水 0 条。
- 绑定后与预扣后、MQ 提交前的两次故障均强杀独立 JVM，退出码 137。绑定后的原订单号经续租恢复，预扣后的原订单号经真实 Broker 回查提交。最终每人最多 1 单，合计 2 张，MySQL 与 Redis 均剩 0，提交和预扣索引排空。
- 单独复跑并发用例，验证已落库的旧提交索引被清理，账面与 Redis 库存保持一致，旧请求状态写回保留新请求的索引和订单号。补验 1 项通过。
- 完整七项运行约 86 秒，可用内存最低 670 MiB，宿主机 OOM 计数增量 0。每场都清理临时数据库、测试账号和 Redis DB 12，Redis DB 0 键数量一致。分布式发行包 HTTP E2E 仍待补验。
- 云端验收输入为固定的 ISSUE-012 源码快照 `2dc25c0f`，补验测试文件为 `83a7fb76`。工作区随后新增的订单确认体验改动留在本地，随 ISSUE-013 验收。验收后回收临时源码、凭证和专用测试消息，保留已启动的清洁 RocketMQ 及空 Topic。
