# StarShow 接口与状态机总契约 (API_CONTRACT.md)

> 所有模块新增或调整对外 REST 接口时，必须同步更新本契约表。
>
> Phase 2～4 项为设计评审稿。精确请求、响应、状态、默认值及失败恢复分别见 [链路二规格](../domains/02-show-inventory.md)、[链路三规格](../domains/03-order-seckill.md)、[链路四规格](../domains/04-payment-reconcile.md)。

## 1. 全局协议规范

- **统一响应体**：`com.starshow.common.result.Result<T>`
  ```json
  {
    "code": "00000",
    "message": "OK",
    "data": {},
    "traceId": "32位十六进制追踪ID",
    "timestamp": 1763625600000
  }
  ```
- **核心规范**：
  - 所有 64 位 ID（`userId`、`showId`、`tierId`、`orderNo`、`ticketNo`、`payNo`）与 `Long` 金额字段（`amountCent`、`priceCent`）在 DB/Java 中统一为 `BIGINT`/`Long`（金额单位为**分**，严禁浮点），受全局 `JacksonLongToStringConfig` 约束，在 JSON 响应中**一律序列化为数字字符串**（如 `"68000"`）；
  - 请求头携带 `Authorization: Bearer <jwt>` 与可选 `X-Trace-Id`；响应头回写 `X-Trace-Id`；
  - 统一 HTTP 状态码约定：未登录/黑名单（`A0101`/`A0102`）返回 HTTP `401`，参数校验失败（`A0103`）返回 HTTP `400`，限流（`B0101`）返回 HTTP `429`，其余领域业务拒绝返回 HTTP `200` + `Result.fail(code, msg)`，未捕获系统异常返回 HTTP `500`。
  - Phase 2～4 拟使用 HTTP `503` 表示暂时不可用。`B0201` 对应售卖期库存不可用，`B0103` 对应创单投递未确认，`C0401` 对应渠道结果暂无法确认。接口可返回明确的重试提示与已有单号。外部时间带时区偏移，金额仍由后端以分计算。

---

## 2. 四条链路接口规划总表

### 链路一：鉴权、实名与预约 (`starshow-user`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `POST /api/v1/auth/register` | 手机号注册（`spring-security-crypto` BCrypt 哈希，`uk_phone` 防重） | 公开 | ✅ Phase 1 已实现 |
| `POST /api/v1/auth/login` | 登录签发 JWT（HS256，含 `sub`/`jti`/`exp`，防账号枚举 `A0108`） | 公开 | ✅ Phase 1 已实现 |
| `POST /api/v1/auth/logout` | 主动登出（`jti` 写入 Redis 黑名单，TTL=剩余有效期） | 登录 | ✅ Phase 1 已实现 |
| `POST /api/v1/users/identity` | 实名认证（GB 11643 校验 + AES-GCM 密文 + HMAC-SHA256 唯一防黄牛 `A0105`） | 登录 | ✅ Phase 1 已实现 |
| `GET /api/v1/users/identity` | 查询脱敏实名状态（`3101**********1234`） | 登录 | ✅ Phase 1 已实现 |
| `POST /api/v1/reservations` | 场次预约（实名校验 + 布隆初筛防误杀 + DB 唯一防重 + 单 Key Hash 计数一致性 + MQ 5.x 绝对时间延迟提醒与定时补发） | 实名 | ✅ Phase 1 已验收 |
| `GET /api/v1/reservations/me` | 查询本人全部预约场次列表（复用 `uk_user_show` 前缀索引，含最新开售时间/版本与下一步引导，按开售时间由近到远排序） | 登录 | ✅ Phase 1.1 已验收 |
| `GET /api/v1/reservations/{showId}/me` | 查询本人该场次预约事实、实名前置资格（`realNameVerified`）、服务端时钟（`serverTime`）、最新官方开售时间/版本（`saleConfigVersion`）、提醒精确语义（`0/1/2/3/4`）、权威入口状态（`entryState`）及下一步引导（`VERIFY_IDENTITY`/`RESERVE`/`WAIT_FOR_SALE`/`ENTER_SECKILL`/`WATCH_RESTOCK`/`RETRY_LATER`/`SALE_ENDED` + `nextActionHint`） | 登录 | ✅ Phase 2 已对齐验收 |
| `GET /api/v1/reservations/{showId}/count` | 查询场次预约人数。热态读取单 Key Hash `{epoch,count}`，有界最终一致（默认 30s 过期不续期，Fencing Token 防旧覆盖；Phase 1.1 已实装 SingleFlight 回源合并、带时间戳微快照 `asOfEpochMs`/`stale`/`countAvailable` 与故障降级） | 公开 | ✅ Phase 1.1 已验收 |
| `GET /api/v1/notifications/me` | 查询本人站内通知列表与购票入口（支持 `REMIND_BEFORE_SALE` 开票提醒与 `SCHEDULE_CHANGED` 改期即时变更两类，`uk_biz_key` 重放仅留一条） | 登录 | ✅ Phase 1.1 已验收 |
| `POST /api/v1/notifications/{notificationId}/read` | 标记站内提醒已读并记录打开时间（区分送达/可见/打开三类指标） | 登录 | ✅ Phase 1.1 已验收 |
| `GET /internal/users/{userId}/verified` | 校验用户实名认证状态（`distributed` 模式下由 `starshow-order` 通过 OpenFeign 调用，强制校验 `X-Internal-Gateway-Secret`） | 内部密钥 | ✅ Phase 3 已验收 |
| `GET /internal/shows/{showId}/basic` | 演出基础元数据内部只读契约（含权威 `saleConfigVersion`、`seatMode` 与 `entryState`，`distributed` 模式下由 `starshow-user` / `starshow-order` 通过 OpenFeign 调用，强制校验 `X-Internal-Gateway-Secret`） | 内部密钥 | ✅ Phase 2 已升级验收 |
| `PUT /internal/shows/{showId}/schedule` | 主办方安全改期开售时间内部契约（覆盖 `INIT`/`PREHEATING`/`PREHEATED`，Redis 原子 `BLOCKED` + 乐观 CAS 推进 `sale_config_version + 1` + 刷新 L2 + 自动联动链路一重排提醒） | 内部密钥 | ✅ Phase 2 已升级验收 |
| `POST /internal/reservations/shows/{showId}/reschedule` | 改期后联动重排该场次已有预约提醒至最新 `saleConfigVersion`（单进程本地调用 / 分布式 OpenFeign，提前入窗/推迟出窗/已开售置 `4`，旧版延迟消息消费端按版本跳过） | 内部密钥 | ✅ Phase 2 已联调验收 |

### 链路二：演出、余票与选座 (`starshow-show`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `GET /api/v1/shows` | 按城市与稳定时间游标（`show_time, id`）浏览演出摘要，游标强绑定筛选条件。 | 公开 | ✅ Phase 2 已验收 |
| `GET /api/v1/shows/{showId}` | 返回权威开售时间、配置版本、座位模式、票档（`Long` 字符串序列化）和入口四态。静态元数据经 Caffeine L1（60s）+ Redis L2 逻辑过期 + 布隆防穿透与 30s 空值缓存。 | 公开 | ✅ Phase 2 已验收 |
| `GET /api/v1/shows/{showId}/stock` | 返回票档余量、快照时间和状态。有效零余量返回 `200/SOLD_OUT`，在售期部分缺失标 `available=null/STOCK_UNAVAILABLE`，整体不可用返回 `503/B0201` + `Retry-After`。 | 公开 | ✅ Phase 2 已验收 |
| `GET /api/v1/shows/{showId}/zones/{zoneId}/seats` | 返回分区布局版本、MSB 最高位优先位图解码结果与走道 `segmentId`；在售期位图缺失或异常返回 `503/B0201`。 | 公开 | ✅ Phase 2 已验收 |
| `POST /internal/shows/{showId}/preheat` | 按 `(showId, saleConfigVersion)` 幂等创建/查询预热任务，Fencing Token Lua 分批写入并全量回读校验后置 `READY`/`PREHEATED`，到 `saleStartTime` 毫秒级准点开放。 | 内部密钥（公网零路由） | ✅ Phase 2 已验收 |
| `GET /internal/shows/{showId}/tiers/{tierId}/snapshot` | 返回用于下单校验的权威价格（分）、`saleConfigVersion`、`layoutVersion`、`maxTicketsPerOrder`、售卖窗口和座位模式。 | 内部密钥 | ✅ Phase 2 已验收 |
| `GET /internal/shows/{showId}/zones/{zoneId}/seats/spec` | 返回分区物理座位列表、所属票档、排座号与走道连续段 `segmentId`（`distributed` 模式下供 `starshow-order` 自主选座与连座校验）。 | 内部密钥 | ✅ Phase 3 已验收 |

### 链路三：抢票下单引擎 V1~V4 (`starshow-order`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `POST /api/v1/seckill/token` | 签发绑定本人、场次与开售版本的短期凭证 `seckillToken`（支持同场次售罄/冲突后快速切换票档重试）。 | 实名 | ✅ Phase 3 已验收 |
| `POST /api/v1/seckill/orders` | 用 `clientRequestId` 经 `seckill_req_bind.lua` 首请超时找回原单号，支持自动配座或自主选座。V1～V3 成功返回 `200`。V4 确认受理返回 `202` + `orderNo`。 | 实名 | ✅ Phase 3 已验收 |
| `GET /api/v1/orders/{orderNo}/status` | 本人 V4 办理进度。尚未落单时带 `showId`。明确返回 `PENDING_CONFIRM`、`WAIT_PAY` 或已证实的 `FAILED`。已落单后返回实际订单状态。 | 登录 | ✅ Phase 3 已验收 |
| `GET /api/v1/orders/current` | Query `showId`，凭登录态找回本人该场次当前在途或有效订单，解决换设备或杀 App 恢复。 | 登录 | ✅ Phase 3 已验收 |
| `GET /api/v1/orders/{orderNo}` | 按基因订单号单分片精准查单（轮询落库、支付状态及关单后异常退款进度） | 登录 | ✅ Phase 3 已验收 |
| `GET /api/v1/orders` | 查询当前用户订单列表（稳定游标分页，仅命中本人单个分片） | 登录 | ✅ Phase 3 已验收 |

### 链路四：支付、故障注入、退款与对账 (`starshow-payment`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `POST /api/v1/payments` | 创建支付单（截止时间取 `min(now+14m30s, order.expireTime-30s)`，超保护窗拒建） | 登录 | Phase 4 |
| `GET /api/v1/payments/{payNo}` | 查询本人支付状态、实际截止、下一步及关单异常退款进度。 | 登录 | Phase 4 |
| `POST /api/v1/payments/mock/pay/{payNo}` | Mock 支付触发器（支持 `?fault=NONE\|DUPLICATE_3X\|DROP_CALLBACK\|DELAY_20S\|RACE_WITH_CLOSE`） | 登录/演练 | Phase 4 |
| `POST /api/v1/payments/callback` | 支付渠道回调验签与幂等入账入口 | 验签 | Phase 4 |
| `POST /api/v1/orders/{orderNo}/cancel` | 本人申请取消未支付订单。确认渠道已关闭且未付后关单并记释放任务。 | 登录 | Phase 4 |
| `GET /api/v1/tickets/order/{orderNo}` | 返回本人出票中状态（含已锁座位预览）或可展示电子票与防伪码。 | 登录 | Phase 4 |
| `POST /api/v1/refunds` | 发起退款（驱动退款 Saga 状态机） | 登录 | Phase 4 |
| `GET /api/v1/refunds/{refundNo}` | 查询本人退款进度和已确认资金结果。 | 登录 | Phase 4 |
| `POST /internal/reconciliation/run` | 手动触发悬挂巡检 / T+1 四方对账（演示与集成测试用） | 内部密钥（公网零路由） | Phase 4 |
| `GET /internal/reconciliation/diffs` | 查询四方对账差异审计表 | 内部密钥（公网零路由） | Phase 4 |

### 链路二至四的新增结果码与 Lua 兼容性

| 契约 | 语义 |
| --- | --- |
| `A0205` | 开售配置或座位布局版本已变化，刷新详情或该分区。 |
| `A0307` / `A0308` | 同一请求 ID 携带不同参数 / 票档价格变化需重新确认。 |
| `A0405` / `A0406` | 支付窗口关闭 / 退款单不可见。 |
| `B0201` / `C0401` | 库存暂不可用 / 渠道结果暂无法确认。 |
| Lua `-1, -2, -3, -4` | 维持余量不足、重复购买、防悬挂拒绝、座位冲突。对应 `A0301, A0302, A0306, A0204`。`A0203` 继续表示票档不存在。 |
| Lua `-5, -6` | 拟增库存或必要位图缺失、门禁关闭或版本/时段不符。准备中或停售用 `A0202`，在售真实资源不可用用 `B0201`，客户端版本过时用 `A0205`。 |

---

## 3. 订单状态机流转矩阵（严格单向 CAS）

| 当前状态 (`expected`) | 允许目标状态 (`target`) | 触发事件 | 关键副作用 |
| --- | --- | --- | --- |
| `WAIT_PAY` | `PAID` | 支付回调 / 关单前查单发现已付 | 写 `pay_trade_no`，同事务记出票待办 |
| `WAIT_PAY` | `CLOSED` | 到期或用户主动取消，渠道确认未付且已关闭 | `active_flag = order_no`，同事务记库存释放任务 |
| `PAID` | `TICKETED` | 电子票消费端出票完成 | 生成 `t_ticket` + HMAC 防伪码 |
| `PAID` / `TICKETED` | `REFUNDING` | 用户申请退款 | 创建 `t_refund_order` 启动 Saga |
| `REFUNDING` | `REFUNDED` | 资金退款、票据失效和库存释放均确认 | `active_flag = order_no`，不重复释放 |
| `CLOSED` / `REFUNDED` | **无（绝对终态）** | 关单后异常到账，或已退订单重复回调 | `CLOSED` 的新确认到账走单一异常退款。`REFUNDED` 的原流水回调幂等返回，异常新流水转资金核对。 |
