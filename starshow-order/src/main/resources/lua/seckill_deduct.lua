-- ============================================================================
-- 自动配座模式：V4 本地事务原子预扣脚本 (seckill_deduct.lua)
-- 所有 KEYS 共享 {showId} Hash Tag，100% 兼容 Redis Cluster，杜绝 CROSSSLOT
-- 严格原则：在第一项写入前完成全部保护性校验（幂等/墓碑/门禁与毫秒窗口/键存在性/限购/余量）
--
-- KEYS[1]: starshow:stock:{showId}:<tierId>           票档实时库存
-- KEYS[2]: starshow:bought:{showId}                   场次已购用户 Set
-- KEYS[3]: starshow:deduct_log:{showId}:<orderNo>     事务消息回查凭证 Hash
-- KEYS[4]: starshow:deduct_pending:{showId}           悬挂扣减扫描 ZSET
-- KEYS[5]: starshow:rollback_done:{showId}:<orderNo>  回补幂等/防悬挂墓碑
-- KEYS[6]: starshow:show:ready:{showId}               链路二权威开售门禁 Hash
-- KEYS[7]: starshow:bought_owner:{showId}             场次已购用户->订单号映射 Hash（换设备找回）
-- KEYS[8]: starshow:seckill:current:{showId}:<userId> 当前提交租约
--
-- ARGV[1]: userId
-- ARGV[2]: qty
-- ARGV[3]: orderNo
-- ARGV[4]: tierId
-- ARGV[5]: nowEpochSec
-- ARGV[6]: ttlSec (默认 86400)
-- ARGV[7]: expectedSaleConfigVersion
-- ARGV[8]: nowEpochMs
-- ARGV[9]: leaseVersion（与 KEYS[8] 同时提供）
--
-- 返回码：
--   1 : 扣减成功（或同 orderNo 幂等确认旧预扣）
--  -1 : 库存不足或数量非法
--  -2 : 用户重复抢购该场次
--  -3 : 命中防悬挂墓碑（该单已被回查回滚，拒绝延迟扣减）
--  -5 : 票档库存键缺失（已开售热数据不可用，绝不当 0 或售罄）
--  -6 : 门禁关闭、不在毫秒售卖窗口 [saleStartEpochMs, saleEndEpochMs) 或配置版本不匹配
-- ============================================================================

local stockKey = KEYS[1]
local boughtKey = KEYS[2]
local deductLogKey = KEYS[3]
local deductPendingKey = KEYS[4]
local rollbackDoneKey = KEYS[5]
local readyKey = KEYS[6]
local boughtOwnerKey = KEYS[7]
local currentKey = KEYS[8]

local userId = ARGV[1]
local qty = tonumber(ARGV[2])
local orderNo = ARGV[3]
local tierId = ARGV[4]
local nowEpochSec = tonumber(ARGV[5])
local ttlSec = tonumber(ARGV[6])
local expectedVersion = ARGV[7]
local nowEpochMs = tonumber(ARGV[8])

if (not qty) or (qty <= 0) or (not nowEpochSec) or (not ttlSec) or (ttlSec <= 0) or (not nowEpochMs) then
    return -1
end

-- 1. 幂等重放：相同 orderNo 若已成功写入同参数 deduct_log，直接确认旧预扣
if redis.call('EXISTS', deductLogKey) == 1 then
    local loggedUser = redis.call('HGET', deductLogKey, 'userId')
    local loggedTier = redis.call('HGET', deductLogKey, 'tierId')
    local loggedQty = redis.call('HGET', deductLogKey, 'qty')
    if loggedUser == userId and loggedTier == tierId and loggedQty == tostring(qty) then
        return 1
    end
    return -2
end

-- 2. 防悬挂墓碑校验
if redis.call('EXISTS', rollbackDoneKey) == 1 then
    return -3
end
if currentKey and (redis.call('HGET', currentKey, 'orderNo') ~= orderNo
    or redis.call('HGET', currentKey, 'leaseVersion') ~= ARGV[9]) then
    return -7
end

-- 3. 链路二权威开售门禁校验（stage IN ('READY','OPEN') + version + [saleStartEpochMs, saleEndEpochMs)）
local stage = redis.call('HGET', readyKey, 'stage')
local readyVer = redis.call('HGET', readyKey, 'version')
local saleStartMs = tonumber(redis.call('HGET', readyKey, 'saleStartEpochMs') or '-1')
local saleEndMs = tonumber(redis.call('HGET', readyKey, 'saleEndEpochMs') or '-1')

if (stage ~= 'READY' and stage ~= 'OPEN')
        or (readyVer ~= expectedVersion)
        or (saleStartMs < 0)
        or (saleEndMs <= saleStartMs)
        or (nowEpochMs < saleStartMs)
        or (nowEpochMs >= saleEndMs) then
    return -6
end

-- 4. 实时库存键真实存在校验（缺失返回 -5，绝不当 0 或售罄）
local stockRaw = redis.call('GET', stockKey)
if not stockRaw then
    return -5
end
local stock = tonumber(stockRaw)
if not stock then
    return -5
end

-- 5. 同场次重复购买校验
if redis.call('SISMEMBER', boughtKey, userId) == 1 then
    return -2
end

-- 6. 余量校验
if stock < qty then
    return -1
end

-- 7. 全部保护性校验通过：原子执行扣减、占位、凭证与悬挂索引写入
redis.call('DECRBY', stockKey, qty)
redis.call('SADD', boughtKey, userId)
redis.call('HSET', boughtOwnerKey, userId, orderNo)
redis.call('HSET', deductLogKey,
    'userId', userId,
    'qty', tostring(qty),
    'orderNo', orderNo,
    'tierId', tierId,
    'saleConfigVersion', expectedVersion,
    'ts', tostring(nowEpochSec)
)
redis.call('EXPIRE', deductLogKey, ttlSec)
redis.call('ZADD', deductPendingKey, nowEpochSec, orderNo)

if currentKey then
    redis.call('HSET', currentKey, 'status', 'PENDING_CONFIRM')
end
return 1
