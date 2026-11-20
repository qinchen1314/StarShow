-- ============================================================================
-- 通用原子幂等回补脚本 (seckill_rollback.lua)
-- 兼容自动配座与自主选座模式，防止重复回补库存造成超卖，且安全清理 bought_owner
-- 所有 KEYS 共享 {showId} Hash Tag，零 CROSSSLOT
--
-- KEYS[1]: starshow:stock:{showId}:<tierId>           票档实时库存
-- KEYS[2]: starshow:bought:{showId}                   场次已购用户 Set
-- KEYS[3]: starshow:rollback_done:{showId}:<orderNo>  回补幂等标记
-- KEYS[4]: starshow:deduct_pending:{showId}           悬挂扣减扫描 ZSET
-- KEYS[5]: starshow:seat:bitmap:{showId}:<zoneId>     分区座位图 Bitmap（无选座时可传占位Key）
-- KEYS[6]: starshow:bought_owner:{showId}             已购用户->订单号映射 Hash（可选）
--
-- ARGV[1]: userId
-- ARGV[2]: qty
-- ARGV[3]: orderNo
-- ARGV[4]: ttlSec (默认 86400)
-- ARGV[5..N]: (可选) 待释放的 seatIndex 列表
--
-- 返回码：
--   1 : 回补成功
--   0 : 已回补过（幂等跳过）
-- ============================================================================

local stockKey = KEYS[1]
local boughtKey = KEYS[2]
local rollbackDoneKey = KEYS[3]
local deductPendingKey = KEYS[4]
local bitmapKey = KEYS[5]
local boughtOwnerKey = KEYS[6]

local userId = ARGV[1]
local qty = tonumber(ARGV[2])
local orderNo = ARGV[3]
local ttlSec = tonumber(ARGV[4]) or 86400

if redis.call('SET', rollbackDoneKey, '1', 'NX', 'EX', ttlSec) == false then
    return 0
end

if qty and qty > 0 and redis.call('EXISTS', stockKey) == 1 then
    redis.call('INCRBY', stockKey, qty)
end

-- 仅当 bought_owner 为空或归属本 orderNo 时释放该用户的同场次已购标记，避免误清另一笔合法订单
local shouldRemoveBought = true
if boughtOwnerKey then
    local curOwner = redis.call('HGET', boughtOwnerKey, userId)
    if curOwner and curOwner ~= orderNo then
        shouldRemoveBought = false
    elseif curOwner == orderNo then
        redis.call('HDEL', boughtOwnerKey, userId)
    end
end
if shouldRemoveBought then
    redis.call('SREM', boughtKey, userId)
end

redis.call('ZREM', deductPendingKey, orderNo)

-- 如有指定座位偏移，且位图存在，批量释放 Bitmap
if #ARGV >= 5 and redis.call('EXISTS', bitmapKey) == 1 then
    for i = 5, #ARGV do
        local idx = tonumber(ARGV[i])
        if idx and idx >= 0 then
            redis.call('SETBIT', bitmapKey, idx, 0)
        end
    end
end

return 1
