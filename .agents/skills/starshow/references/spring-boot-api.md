# Spring Boot 分层、API 规范与双形态运行指南

用于编写 Controller、Service、Facade、配置类与执行本地构建。

## 1. 分层边界红线

- **Controller 层**：仅负责路由映射、参数校验（`@Valid`）、提取当前登录态与 TraceId、调用 Application Service、包装 `Result<T>` 返回。
  - **严禁**：在 Controller 中写业务分支判断、直接注入 Mapper、标注 `@Transactional`、或直接操作底层 Redis Lua / MQ。
- **Application Service / Strategy 层**：负责编排用例、控制事务边界、调用状态机与分布式锁、触发领域事件。
  - 统一使用**构造器注入**（推荐 `@RequiredArgsConstructor`），禁止字段 `@Autowired`。
  - 严禁把数据库 DO / Entity 直接透传给前端，对外一律使用独立 VO / DTO。
- **Domain / 状态机层**：如 `OrderStateMachine`、`GeneSnowflakeIdGenerator`、`CryptoUtil`，保持纯 Java、无框架依赖、易于高速单测。
- **Infrastructure 层**：Mapper、Lua 脚本执行器、RocketMQ 生产者/消费者、Mock 支付沙箱客户端。

## 2. 单进程聚合与微服务双形态运行规范

本项目支持两种运行形态：
- **单进程极速开发态（`starshow-boot-all`，`spring.profiles.active=monolith`）**
- **分布式微服务态（`gateway + user + show + order + payment`，`distributed`）**

**硬性防死锁规则**：
- 跨模块同步调用统一面向 `starshow-api` 中的接口（`UserFacade`、`ShowReadFacade`）。
- 在 `monolith` 模式下，直接注入目标模块本地 `@Service` 实现类 Bean（内存方法直调）；**严禁在单进程内通过 HTTP OpenFeign 调 `localhost` 自调用**，否则高并发压测时会立刻耗尽 Tomcat 线程池引发死锁。
- 4 个业务模块打包插件已配置 `<classifier>exec</classifier>`，确保普通 jar 可被 `starshow-boot-all` 安全依赖。

## 3. REST API 与统一返回契约

- 路由统一前缀：`/api/v1/{资源复数}`（例如 `/api/v1/users`、`/api/v1/shows`、`/api/v1/seckill`、`/api/v1/orders`、`/api/v1/payments`）。
- 返回结构统一使用 `com.starshow.common.result.Result<T>`，错误码统一归口 `ErrorCode` 枚举。
- 异步抢票（V4）受理成功统一返回 `Result.accepted(data, ...)`（`code = "00202"`）+ HTTP `202 Accepted`。
- 全链路必须在 HTTP 入口过滤并透传 `X-Trace-Id` 到 SLF4J MDC（`TraceIdContext`），请求结束在 `finally` 中 `TraceIdContext.clear()` 防线程复用污染。

## 4. 本机编译与测试命令

仓库根目录执行（已锁定 `--release 21` 与 Lombok 兼容配置）：

```powershell
# 全模块编译与单元测试
mvn -B clean test

# 仅跑指定模块及其上游依赖测试（如 starshow-order）
mvn -B -pl starshow-order -am test

# 启动 Core 基础设施（MySQL 8 / Redis 7 / RocketMQ 5.3 / Nacos）
docker compose -f docker/docker-compose.yml up -d
```
