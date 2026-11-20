# 代码审查与提交前自查清单

每次功能完成、提交 Commit 或做 Code Review 时逐项核对，问题按严重级别（高危/中危/建议）列出并附精确文件与行号。

## 1. 架构与高并发红线核查

- [ ] Controller 是否只做 HTTP 映射与参数校验，零业务逻辑、零 Mapper 直调、零事务注解？
- [ ] 是否存在用 DB 回写或删缓存覆盖售卖期实时库存 Key（`starshow:stock:*` / `starshow:seat:bitmap:*`）的隐患？
- [ ] Lua 脚本与 Redis Key 是否全部通过 `RedisKeys` 生成并共享 `{showId}` Hash Tag（无 `CROSSSLOT` 风险）？
- [ ] 是否出现 `KEYS *` 或全库 `SCAN`？悬挂扣减是否严格走 `ZRANGEBYSCORE`？
- [ ] V4 消费端更新 `t_ticket_tier` 时是否做了行锁保护（限并发或批量聚合）？
- [ ] 单进程 `monolith` 模式下跨模块调用是否走 `Facade` 本地 Bean，无 HTTP 自调用死锁风险？

## 2. 一致性、状态机与幂等核查

- [ ] 订单状态变更 SQL 是否显式带有 `WHERE order_no = ? AND status = ?`（数据库级 CAS）？
- [ ] 订单 `CLOSED` 后遇到滞后回调，是否确保绝不复活回 `PAID` 并转入自动退款？
- [ ] 事务消息回查返回 `ROLLBACK` 前，是否已写入 `rollback_done` 防悬挂墓碑？
- [ ] `t_mq_consume_log` 写入与订单落库是否在同一本地事务内提交？
- [ ] 金额字段与运算是否全链路使用 `long`（分），零 `float`/`double` 精度误差？
- [ ] 对外接口返回的 64 位 ID（`orderNo`、`ticketNo`、`payNo`）是否经过 `Long -> String` 序列化？

## 3. 隐私与 GitHub 开源脱敏核查

- [ ] 身份证号是否经 `AES-256-GCM` 加密且唯一索引走 `HMAC-SHA256(pepper)`？日志中是否严禁打印明文身份证/手机号/密码/JWT？
- [ ] `git diff --cached` 中是否零真实密钥、零个人邮箱、零本机绝对路径、零本地代理地址？
- [ ] `.env` 及临时压测密钥文件是否未被 `git add`？

## 4. `prove-it` 测试防弱化核查

- [ ] 是否实际运行了目标测试并贴出真实通过日志（而非只靠“编译通过”）？
- [ ] `git diff` 中的测试文件是否**没有**删除/注释断言、调低并发数或用假 Mock 绕开核心验证路径？
