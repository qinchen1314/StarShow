# StarShow（星演票务）· 高并发演唱会票务系统

[![CI](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen)]()
[![RocketMQ](https://img.shields.io/badge/RocketMQ-5.3.0-blue)]()

**StarShow** 是一套面向真实高并发票务场景设计的工业级 Java 后端工程，完整覆盖「实名防黄牛预约 → 多级缓存与分区 Bitmap 选座 → V1→V4 演进式抢票引擎 → Mock 支付故障注入、超时关单竞速、退款 Saga 与四方对账」四条核心链路。

---

## 核心架构亮点

- **四版本抢票架构同仓演进（V1 → V4 策略一键切换）**
  - `V1`：纯 MySQL 乐观锁同步下单（基线对照组）
  - `V2`：Redisson 场次级粗粒度锁同步下单
  - `V3`：Redisson 票档级细粒度锁同步下单
  - `V4`：Redis Lua 无锁原子预扣（支持自动配座与 Bitmap 连座选座）+ RocketMQ 事务消息 + 防悬挂墓碑 + ZSET 悬挂索引 + 三层幂等落单
- **强一致与五层纵深幂等**
  - `UK(user_id, show_id, active_flag)` 兼顾“一人一场次一单”与“关单后合法重抢”
  - 基因法 Snowflake（`1 + 41 + 6 + 12 + 4` bit）实现按 `user_id` 与 `order_no` 均零广播精准路由
  - 订单状态机严格单向 CAS，`CLOSED` 永不复活，滞后支付自动转原路退款
- **安全与隐私工程**
  - 身份证 `AES-256-GCM` 密文落库 + `HMAC-SHA256(pepper)` 确定性唯一哈希防彩虹表
- **一套代码、两种运行形态**
  - 本地开发支持 `starshow-boot-all` 单进程秒启（Facade 本地直调，零自调用死锁），生产/演示支持网关 + 4 微服务独立部署
  - Docker Compose 分层编排：Core 核心栈（< 3GB 内存）+ Ops 可观测/调度扩展栈按需启用

---

## 仓库结构

```text
starshow-ticket/
├── .agents/skills/         # 项目级开发宪法 (starshow) 与实证验收门禁 (prove-it)
├── docker/                 # Core 核心栈 & Ops 扩展栈 Docker Compose、MySQL DDL/Seed、RocketMQ 配置
├── docs/                   # 文档治理中心：PROGRESS 账本、4 大业务板块映射、API 契约、ADR 与压测报告
├── starshow-common/        # 统一返回、错误码、基因Snowflake、状态机、AES/HMAC加密、Redis Key契约
├── starshow-api/           # 内部 Facade 契约、DTO、RocketMQ Event Schema
├── starshow-gateway/       # Spring Cloud Gateway 入口路由与限流
├── starshow-user/          # 链路一：实名防黄牛 + 布隆预约蓄水 + 开票延迟提醒
├── starshow-show/          # 链路二：L1+L2 多级缓存 + 分区 Bitmap 座位图 + 开票预热
├── starshow-order/         # 链路三+四：V1~V4 抢票引擎 + Lua 原子脚本 + 事务消息 + 关单出票
├── starshow-payment/       # 链路四：支付流水 + Mock 故障注入沙箱 + 退款 Saga + 四方对账
└── starshow-boot-all/      # 本地极速开发单进程聚合启动器
```

---

## 快速启动

### 1. 配置环境变量
```bash
cp .env.example .env
```

### 2. 启动 Core 基础设施（MySQL 8 / Redis 7 / RocketMQ 5.3 / Nacos）
```bash
docker compose -f docker/docker-compose.yml up -d
```
> 如需开启 Sentinel / Prometheus / Grafana / XXL-Job：
> ```bash
> docker compose -f docker/docker-compose.yml -f docker/docker-compose.ops.yml --profile ops up -d
> ```

### 3. 编译并运行单元测试
```bash
mvn clean test
```

---

## 演进路线图

- [x] **Phase 0 (`v0.1.0-infra`)**：Maven 多模块骨架、Docker 分层栈、MySQL 14 表 DDL + 种子数据、3 套 Lua 原子契约、`starshow-common` 底座与核心单测
- [ ] **Phase 1 (`v0.2.0-chain1`)**：链路一（JWT 黑名单 + 实名 HMAC 防黄牛 + 布隆预约 + RocketMQ 5.x 延迟提醒）
- [ ] **Phase 2 (`v0.3.0-chain2`)**：链路二（Caffeine L1 + Redis L2 逻辑过期 + 分区 Bitmap 座位图 + 开票预热）
- [ ] **Phase 3 (`v0.4.0-chain3`)**：链路三（V1→V4 抢票策略引擎 + 事务消息防悬挂回查 + 基因分表）
- [ ] **Phase 4 (`v0.5.0-chain4`)**：Mock 支付、关单、出票、退款 Saga 和四方对账已实现，核心验收通过。发布与剩余复验见 [进度账本](docs/PROGRESS.md)。
- [ ] **Phase 5 (`v0.9.0-bench`)**：已完成同一 5,001 用户的 V1～V4 基线压测，Grafana 与多实例复验待验收。[压测报告](docs/benchmarks/BENCHMARK_V1_TO_V4.md)
- [ ] **Phase 6 (`v1.0.0-release`)**：零构建单页演示台与开源发布
