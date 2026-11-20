# 正反代码范例（✅ / ❌ 避坑速查）

把 StarShow 中最容易写错的 5 类高危问题固化为代码对比。

---

## 1. Redis Lua Key 命名与 Cluster Hash Tag

### ❌ 错误示范：混用 Hash Tag，Cluster 下直接报 `CROSSSLOT`
```java
List<String> keys = List.of(
    "starshow:stock:{" + showId + "}:" + tierId,     // Slot 由 showId 决定
    "starshow:bought:{" + showId + "}",              // Slot 由 showId 决定
    "starshow:deduct_log:{" + orderNo + "}"          // ❌ Slot 由 orderNo 决定 -> CROSSSLOT!
);
```

### ✅ 正确示范：统一经 `RedisKeys` 生成，全部共享 `{showId}`
```java
List<String> keys = List.of(
    RedisKeys.stock(showId, tierId),
    RedisKeys.boughtUsers(showId),
    RedisKeys.deductLog(showId, orderNo),
    RedisKeys.deductPendingZset(showId),
    RedisKeys.rollbackDone(showId, orderNo)
);
```

---

## 2. 订单超时关单后遭遇滞后支付回调

### ❌ 错误示范：强行把已关闭订单复活成已支付（必然导致超卖！）
```java
// ❌ 库存可能已被关单回补并被其他人买走，强行改回 PAID 立刻超卖
orderMapper.updateStatusForce(orderNo, OrderStatus.PAID.name());
```

### ✅ 正确示范：状态机 CAS + 拒复活 + 自动转异常原路退款
```java
int updated = orderMapper.casMarkPaid(
    orderNo, OrderStatus.WAIT_PAY.name(), OrderStatus.PAID.name(), channelTradeNo
);
if (updated == 0) {
    OrderDO current = orderMapper.selectByOrderNo(orderNo);
    if (OrderStatus.CLOSED.name().equals(current.getStatus())) {
        // 绝不复活 CLOSED 订单：标记异常支付并自动发起原路退款 Saga
        paymentService.markAbnormalPaidAndTriggerRefund(orderNo, channelTradeNo);
        return;
    }
    // 已是 PAID / TICKETED：重复回调，幂等直接返回成功
}
```

---

## 3. 身份证防黄牛哈希与密文存储

### ❌ 错误示范：裸 SHA-256（低熵身份证几秒撞库还原）或明文落库
```java
// ❌ 裸 SHA-256 无服务端 Pepper，脱库后极易彩虹表反推全部身份证
String hash = DigestUtils.sha256Hex(idCard);
```

### ✅ 正确示范：HMAC-SHA256(pepper) 唯一防重 + AES-256-GCM 随机 IV 加密
```java
String idCardHmac = CryptoUtil.hmacSha256Hex(idCard, securityProps.getIdcardPepper());
String idCardCipher = CryptoUtil.encryptAesGcm(idCard, securityProps.getAesKey());
String idCardMasked = CryptoUtil.maskIdCard(idCard);
```

---

## 4. 单进程聚合模式下的跨模块调用

### ❌ 错误示范：单进程里走 HTTP Feign 调本机端口（压测必触发线程池自调用死锁）
```java
// ❌ monolith 模式下 HTTP 调自己，高并发时外层请求占满 Tomcat 线程，内层永远拿不到线程
Boolean verified = restTemplate.getForObject("http://127.0.0.1:8090/internal/users/" + uid, Boolean.class);
```

### ✅ 正确示范：面向 `starshow-api` Facade 接口编程
```java
// monolith 模式直调本地 Bean；distributed 模式切换为 OpenFeign 代理
if (!userFacade.isRealNameVerified(userId)) {
    throw new BizException(ErrorCode.IDENTITY_REQUIRED);
}
```

---

## 5. V4 消费端更新 DB 物理库存

### ❌ 错误示范：64 并发单条争抢同一行 InnoDB 锁
```java
// ❌ 高并发全速单条 UPDATE 同一票档行，锁等待飙升导致消费严重积压
@RocketMQMessageListener(topic = MqTopics.ORDER_CREATE, consumeThreadMax = 64)
public void onMessage(OrderCreateEvent event) {
    ticketTierMapper.deductStock(event.getTierId(), 1);
}
```

### ✅ 正确示范：控制消费并发度削峰匀速落库，或按票档批量聚合扣减
```java
// 控制对 DB 的匀速落库并发，或对同票档批次事件聚合为单次 UPDATE available_stock = available_stock - batchQty
ticketTierMapper.deductStockOptimistic(tierId, totalBatchQty);
```
