# StarShow 总测试说明

后续测试统一在已部署中间件的云服务器上串行执行。Maven 单测、浏览器验收、真实中间件联调、进程故障演练和压测都使用云端测试资源。

## 云端执行约定

1. 开跑前确认前一轮测试已退出。固定本轮源码快照和构建目录，记录测试套件、环境、进程或容器入口。保留同一个用户池和业务断言，便于比较重跑结果。
2. 真实中间件验收使用单独的 MySQL 测试库、最小权限临时账号、专用 Redis DB 和本轮应用实例。检查 Redis 测试 DB 入场为空。RocketMQ 用已经划给测试的实例或 Topic，避开部署环境的数据、消息和运行中任务。
3. 有限内存下串行编译、启动测试实例和执行剧本，设置 JVM 堆和并发上限。释放资源前保留日志摘要、断言指标和脱敏样例。退出时仅清理本轮创建的资源，并复核进程、端口及默认库哨兵。

## 测试入口

| 级别 | 云端命令或验收入口 | 需要保留的证据 |
| --- | --- | --- |
| L1 单元、接口与页面 | `mvn -B test`。确认页时钟用 `node --test web-console/order-confirmation.test.mjs`。 | 测试总数、失败和跳过数，接口兼容及浏览器行为断言。 |
| L2 并发与库存 | 启用 `starshow.live.enabled=true`，执行对应链路的真实中间件测试。 | 初始库存、有效订单数、Redis 扣减数、超卖与重复单数。 |
| L3 故障与恢复 | 在隔离库和消息环境运行指定链路测试。ISSUE-012 的恢复测试先结束，再执行 ISSUE-013 的 120 秒延迟落库与付款保护窗剧本。 | 故障注入点、原单号连续性、持久化和恢复断言。 |
| L4 压测 | 使用同一套 5,000+ 用户 JWT 池跑 V1～V4，V4 同时量化受理与落库。 | TPS、P99、MQ 排空耗时、零超卖和零重复对账结果。 |

### ISSUE-012 与 ISSUE-013 串行复验

先用 `starshow_c3_` 前缀的隔离库跑 ISSUE-012，再为 ISSUE-013 新建 `starshow_c4_` 前缀的隔离库。每轮通过环境变量设置 MySQL、Redis、RocketMQ 与测试签名配置。

```bash
mvn -B -T 1 -pl starshow-boot-all -am \
  -Dtest=Chain3LiveMiddlewareVerificationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dstarshow.live.enabled=true -Dstarshow.live.crash.enabled=true -DforkCount=1 test

mvn -B -T 1 -pl starshow-boot-all -am \
  '-Dtest=Chain4LiveMiddlewareVerificationTest#resumesOriginalOrderAcross120SecondDeliveryDelayAndRespectsPaymentGuard' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dstarshow.live.enabled=true -DforkCount=1 test
```

按 Issue 记录对应命令、实际执行环境和未验证项。关闭条件以实跑证据为准。[Issue 验收规范](./issues/README.md) · [压测口径](./benchmarks/BENCHMARK_V1_TO_V4.md)

### ISSUE-015 候场隔离复验

在云端临时目录为两套 Redis 分别分配回环端点。常规业务测试 Redis 与候场 Redis 使用独立实例和非零测试 DB。为候场实例保留本轮 AOF 目录，且固定回环端口。为 MySQL 使用 `starshow_queue_` 前缀的临时库。

```bash
mvn -o -B -T 1 -pl starshow-boot-all,starshow-gateway -am \
  -Dtest=WaitingRoomLiveVerificationTest,QueueReadTokenTest,JwtAuthGlobalFilterTest,ShowScheduleServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dstarshow.waiting-room.live=true -DforkCount=1 test
```

测试进程和故障驱动共享本轮的 `WAITING_ROOM_FAULT_DIR`。驱动在 `stop-queue` 出现后停止本轮候场实例并写入 `queue-offline`。收到 `start-queue` 后使用原端点和 AOF 目录启动候场实例，确认可连后写入 `queue-restored`。测试要求 HTTP 状态和两个买家的名次在同一个 10 秒窗口内恢复。后端结束后再用 `target/issue-015-evidence/waiting-room.json` 回放浏览器用例。
