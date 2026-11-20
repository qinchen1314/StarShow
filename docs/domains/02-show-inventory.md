# 板块 02：演出元数据、多级缓存与分区 Bitmap 选座（链路二）

- **映射代码模块**：`starshow-show`
- **计划阶段**：Phase 2（`v0.3.0-chain2`）
- **状态**：⬜ 未开始

## 1. 数据与中间件归属
- **MySQL 表**：`t_show`、`t_ticket_tier`、`t_seat`
- **Redis Key**：`starshow:show:detail:<showId>`、`starshow:lock:show_rebuild:<showId>`、`starshow:seat:bitmap:{showId}:<zoneId>`、`starshow:stock:{showId}:<tierId>`（预热写入）
- **对外内部契约**：实现 `com.starshow.api.facade.ShowReadFacade`

## 2. 核心设计要点
1. **冷热严格分治**：静态详情与票档价格走 Caffeine (L1) + Redis 逻辑过期 (L2)；实时余票直接读 Redis（热点场次允许 500ms~1s 极短 L1 快照），**严禁 DB 回写或 Canal 覆盖售卖期库存 Key**。
2. **三防缓存体系**：
   - 防穿透：Redisson `RBloomFilter` 拦截非法 `showId` + 空值短缓存 30s；
   - 防击穿：演出详情逻辑过期 + 单线程互斥锁异步重建；
   - 防雪崩：基础 TTL + 随机抖动（0~300s）。
3. **分区 Bitmap 座位图**：按 `(showId, zoneId)` 拆分 Bitmap，每个区域 1000~2000 座仅占 125~250 字节，杜绝 BigKey；接口返回区域座位矩阵供前端直接渲染红绿格。
4. **开票前预热闭环（Preheat）**：提供预热服务/接口，将演出详情、各票档初始库存、空座位 Bitmap、已预约布隆一并加载入 Redis，并将场次置为 `PREHEATED`。

## 3. Prove-It 验收红线（Phase 2 完成标准）
- 预热后 Redis 中实时库存与座位 Bitmap 准确初始化；
- 非法 `showId` 请求被布隆过滤器拦截，MySQL 查询数为 0；
- 并发请求逻辑过期热点 `showId` 时仅触发 1 次 DB 重建，其余线程直接返回旧值零阻塞；
- `GET /api/v1/shows/10002/zones/HALL_A/seats` 正确返回 10×10 座位状态矩阵。
