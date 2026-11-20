# StarShow 接口与状态机总契约 (API_CONTRACT.md)

> 所有模块新增或调整对外 REST 接口时，必须同步更新本契约表。

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
  - 所有 64 位 ID（`userId`、`showId`、`tierId`、`orderNo`、`ticketNo`、`payNo`）在 JSON 中**一律序列化为 String**（由 `JacksonLongToStringConfig` 强制保障）；
  - 所有金额字段均以 `_cent` / `Cent` 结尾，单位为**分**（整型）；
  - 请求头携带 `Authorization: Bearer <jwt>` 与可选 `X-Trace-Id`；响应头回写 `X-Trace-Id`。

---

## 2. 四条链路接口规划总表

### 链路一：鉴权、实名与预约 (`starshow-user`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `POST /api/v1/auth/register` | 手机号注册（BCrypt 密码哈希） | 公开 | Phase 1 |
| `POST /api/v1/auth/login` | 登录签发 JWT（含 `jti`） | 公开 | Phase 1 |
| `POST /api/v1/auth/logout` | 主动登出（`jti` 写入 Redis 黑名单） | 登录 | Phase 1 |
| `POST /api/v1/users/identity` | 实名认证（AES-GCM 密文 + HMAC-SHA256 唯一防黄牛） | 登录 | Phase 1 |
| `GET /api/v1/users/identity` | 查询脱敏实名状态 | 登录 | Phase 1 |
| `POST /api/v1/reservations` | 场次预约（布隆过滤 + DB 唯一防重 + 计数 + MQ 延迟提醒） | 实名 | Phase 1 |
| `GET /api/v1/reservations/{showId}/count` | 查询场次实时预约蓄水人数 | 公开 | Phase 1 |

### 链路二：演出、余票与选座 (`starshow-show`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `GET /api/v1/shows` | 演出列表查询 | 公开 | Phase 2 |
| `GET /api/v1/shows/{showId}` | 演出详情（Caffeine L1 + Redis L2 逻辑过期 + 布隆防穿透） | 公开 | Phase 2 |
| `GET /api/v1/shows/{showId}/stock` | 实时余票查询（读 Redis，热点 L1 极短快照） | 公开 | Phase 2 |
| `GET /api/v1/shows/{showId}/zones/{zoneId}/seats` | 分区 Bitmap 实时座位图矩阵 | 公开 | Phase 2 |
| `POST /api/v1/shows/{showId}/preheat` | 开票前库存/详情/Bitmap/布隆一键预热（管理端） | 内部/管理 | Phase 2 |

### 链路三：抢票下单引擎 V1~V4 (`starshow-order`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `POST /api/v1/seckill/token` | 获取一次性防重抢票凭证 `seckillToken` | 实名 | Phase 3 |
| `POST /api/v1/seckill/orders` | 抢票下单（支持自动配座 / 选座 Bitmap；V1~V3 返回 `200`，V4 返回 `202` + `orderNo`） | 实名 | Phase 3 |
| `GET /api/v1/orders/{orderNo}` | 按基因订单号单分片精准查单（轮询落库与支付状态） | 登录 | Phase 3 |
| `GET /api/v1/orders` | 查询当前用户订单列表 | 登录 | Phase 3 |
| `GET /api/v1/tickets/order/{orderNo}` | 查询已履约电子票与防伪核销码 | 登录 | Phase 4 |

### 链路四：支付、故障注入、退款与对账 (`starshow-payment`)
| 方法与路径 | 说明 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| `POST /api/v1/payments` | 创建支付单（默认 `14m30s` 错峰过期） | 登录 | Phase 4 |
| `POST /api/v1/payments/mock/pay/{payNo}` | Mock 支付触发器（支持 `?fault=NONE\|DUPLICATE_3X\|DROP_CALLBACK\|DELAY_20S\|RACE_WITH_CLOSE`） | 登录/演练 | Phase 4 |
| `POST /api/v1/payments/callback` | 支付渠道回调验签与幂等入账入口 | 验签 | Phase 4 |
| `POST /api/v1/refunds` | 发起退款（驱动退款 Saga 状态机） | 登录 | Phase 4 |
| `POST /api/v1/reconciliation/run` | 手动触发悬挂巡检 / T+1 四方对账（演示与集成测试用） | 内部/管理 | Phase 4 |
| `GET /api/v1/reconciliation/diffs` | 查询四方对账差异审计表 | 内部/管理 | Phase 4 |

---

## 3. 订单状态机流转矩阵（严格单向 CAS）

| 当前状态 (`expected`) | 允许目标状态 (`target`) | 触发事件 | 关键副作用 |
| --- | --- | --- | --- |
| `WAIT_PAY` | `PAID` | 支付回调 / 关单前查单发现已付 | 写 `pay_trade_no` + 发 `TOPIC_TICKET_ISSUE` |
| `WAIT_PAY` | `CLOSED` | 15min 超时关单 / 用户主动取消 | `active_flag = order_no` + `seckill_rollback.lua` 幂等回补 |
| `PAID` | `TICKETED` | 电子票消费端出票完成 | 生成 `t_ticket` + HMAC 防伪码 |
| `PAID` / `TICKETED` | `REFUNDING` | 用户申请退款 | 创建 `t_refund_order` 启动 Saga |
| `REFUNDING` | `REFUNDED` | 退款 Saga 全部完成 | `active_flag = order_no` + 回补库存与座位 |
| `CLOSED` / `REFUNDED` | **无（绝对终态）** | 滞后支付回调 | **严禁复活**；写 `ABNORMAL_PAID` 并自动原路退款 |
