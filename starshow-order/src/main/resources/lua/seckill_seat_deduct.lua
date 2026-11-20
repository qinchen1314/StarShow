-- ============================================================================
-- 自主选座模式：Bitmap 多座/连座原子预扣脚本 (seckill_seat_deduct.lua)
-- 所有 KEYS 共享 {showId} Hash Tag，支持一次锁定 1~N 个座位，要么全成要么全败
-- 严格原则：在第一项写入前完成全部保护性校验；位图缺失返回 -5，绝不当全空！
--
-- KEYS[1]: starshow:stock:{showId}:<tierId>           票档实时总库存
-- KEYS[2]: starshow:bought:{showId}                   场次已购用户 Set
-- KEYS[3]: starshow:deduct_log:{showId}:<orderNo>     事务消息回查凭证 Hash
-- KEYS[4]: starshow:deduct_pending:{showId}           悬挂扣减扫描 ZSET
-- KEYS[5]: starshow:rollback_done:{showId}:<orderNo>  回补幂等/防悬挂墓碑
-- KEYS[6]: starshow:show:ready:{showId}               链路二权威开售门禁 Hash
-- KEYS[7]: starshow:bought_owner:{showId}             场次已购用户->订单号映射 Hash
-- KEYS[8]: starshow:seat:bitmap:{showId}:<zoneId>     分区座位图 Bitmap
--
-- ARGV[1]: userId
-- ARGV[2]: orderNo
-- ARGV[3]: tierId
-- ARGV[4]: zoneId
-- ARGV[5]: nowEpochSec
-- ARGV[6]: ttlSec
-- ARGV[7]: expectedSaleConfigVersion
-- ARGV[8]: nowEpochMs
-- ARGV[9..N]: 待锁定的 seatIndex 列表（0-based 互不重复非负整数）
--
-- 返回数组（首元素为状态码）：
--   {1}                    : 锁座并扣减成功（或同 orderNo 幂等确认）
--   {-1}                   : 库存不足或参数非法
--   {-2}                   : 用户重复抢购该场次
--   {-3}                   : 命中防悬挂墓碑
--   {-4, conflictIdx...}   : 所选座位中至少有一个已被占用，附带全部冲突偏移
--   {-5}                   : 票档库存键或分区座位 Bitmap 键缺失（绝不当全空）
--   {-6}                   : 门禁关闭、不在毫秒售卖窗口或配置版本不匹配
-- ============================================================================

local stockKey = KEYS[1]
local boughtKey = KEYS[2]
local deductLogKey = KEYS[3]
local deductPendingKey = KEYS[4]
local rollbackDoneKey = KEYS[5]
local readyKey = KEYS[6]
local boughtOwnerKey = KEYS[7]
local bitmapKey = KEYS[8]

local userId = ARGV[1]
local orderNo = ARGV[2]
local tierId = ARGV[3]
local zoneId = ARGV[4]
local nowEpochSec = tonumber(ARGV[5])
local ttlSec = tonumber(ARGV[6])
local expectedVersion = ARGV[7]
local nowEpochMs = tonumber(ARGV[8])

local seatCount = #ARGV - 8
if seatCount <= 0 or (not nowEpochSec) or (not ttlSec) or (ttlSec <= 0) or (not nowEpochMs) then
    return {-1}
end

-- 解析并校验座位索引合法且互不重复
local seats = {}
local seen = {}
local seatsStr = ''
for i = 9, #ARGV do
    local idx = tonumber(ARGV[i])
    if (not idx) or (idx < 0) or (math.floor(idx) ~= idx) or seen[idx] then
        return {-1}
    end
    seen[idx] = true
    table.insert(seats, idx)
    if seatsStr == '' then
        seatsStr = tostring(idx)
    else
        seatsStr = seatsStr .. ',' .. tostring(idx)
    end
end

-- 1. 幂等重放：相同 orderNo 若已成功写入同参数 deduct_log，直接确认旧预扣
if redis.call('EXISTS', deductLogKey) == 1 then
    local loggedUser = redis.call('HGET', deductLogKey, 'userId')
    local loggedTier = redis.call('HGET', deductLogKey, 'tierId')
    local loggedZone = redis.call('HGET', deductLogKey, 'zoneId')
    local loggedSeats = redis.call('HGET', deductLogKey, 'seatIndices')
    if loggedUser == userId and loggedTier == tierId and loggedZone == zoneId and loggedSeats == seatsStr then
        return {1}
    end
    return {-2}
end

-- 2. 防悬挂墓碑校验
if redis.call('EXISTS', rollbackDoneKey) == 1 then
    return {-3}
end

-- 3. 链路二权威开售门禁校验
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
    return {-6}
end

-- 4. 实时库存键与分区 Bitmap 键存在性校验（缺失返回 -5，绝不当全空）
local stockRaw = redis.call('GET', stockKey)
if (not stockRaw) or (redis.call('EXISTS', bitmapKey) == 0) then
    return {-5}
end
local stock = tonumber(stockRaw)
if not stock then
    return {-5}
end

-- 5. 同场次重复购买校验
if redis.call('SISMEMBER', boughtKey, userId) == 1 then
    return {-2}
end

-- 6. 余量校验
if stock < seatCount then
    return {-1}
end

-- 7. 第一阶段全量校验目标座位空闲 (GETBIT == 0)，收集全部冲突索引
local conflicts = {}
for _, idx in ipairs(seats) do
    if redis.call('GETBIT', bitmapKey, idx) == 1 then
        table.insert(conflicts, idx)
    end
end
if #conflicts > 0 then
    local res = {-4}
    for _, c in ipairs(conflicts) do
        table.insert(res, c)
    end
    return res
end

-- 8. 第二阶段：全部校验通过，原子批量锁座 + 扣库存 + 写已购/归属/回查凭证/悬挂索引
for _, idx in ipairs(seats) do
    redis.call('SETBIT', bitmapKey, idx, 1)
end

redis.call('DECRBY', stockKey, seatCount)
redis.call('SADD', boughtKey, userId)
redis.call('HSET', boughtOwnerKey, userId, orderNo)
redis.call('HSET', deductLogKey,
    'userId', userId,
    'qty', tostring(seatCount),
    'orderNo', orderNo,
    'tierId', tierId,
    'zoneId', zoneId,
    'seatIndices', seatsStr,
    'saleConfigVersion', expectedVersion,
    'ts', tostring(nowEpochSec)
)
redis.call('EXPIRE', deductLogKey, ttlSec)
redis.call('ZADD', deductPendingKey, nowEpochSec, orderNo)

return {1}
