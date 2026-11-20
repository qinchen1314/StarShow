-- ============================================================================
-- 自主选座模式：Bitmap 多座/连座原子预扣脚本 (seckill_seat_deduct.lua)
-- 所有 KEYS 共享 {showId} Hash Tag，支持一次锁定 1~N 个座位，要么全成要么全败
--
-- KEYS[1]: starshow:stock:{showId}:<tierId>           票档实时总库存
-- KEYS[2]: starshow:bought:{showId}                   场次已购用户 Set
-- KEYS[3]: starshow:deduct_log:{showId}:<orderNo>     事务消息回查凭证 Hash
-- KEYS[4]: starshow:deduct_pending:{showId}           悬挂扣减扫描 ZSET
-- KEYS[5]: starshow:rollback_done:{showId}:<orderNo>  回补幂等/防悬挂墓碑
-- KEYS[6]: starshow:seat:bitmap:{showId}:<zoneId>     分区座位图 Bitmap
--
-- ARGV[1]: userId
-- ARGV[2]: orderNo
-- ARGV[3]: tierId
-- ARGV[4]: zoneId
-- ARGV[5]: nowEpochSec
-- ARGV[6]: ttlSec
-- ARGV[7..N]: 待锁定的 seatIndex 列表（0-based 非负整数）
--
-- 返回码：
--   1 : 锁座并扣减成功
--  -1 : 库存不足或未预热
--  -2 : 用户重复抢购该场次
--  -3 : 命中防悬挂墓碑
--  -4 : 所选座位中至少有一个已被占用（冲突）
-- ============================================================================

if redis.call('EXISTS', KEYS[5]) == 1 then
    return -3
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end

local seatCount = #ARGV - 6
if seatCount <= 0 then
    return -1
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if (not stock) or (stock < seatCount) then
    return -1
end

-- 第一阶段：校验全部目标座位均空闲 (GETBIT == 0)
local seatsStr = ''
for i = 7, #ARGV do
    local idx = tonumber(ARGV[i])
    if (not idx) or (idx < 0) or (redis.call('GETBIT', KEYS[6], idx) == 1) then
        return -4
    end
    if seatsStr == '' then
        seatsStr = tostring(idx)
    else
        seatsStr = seatsStr .. ',' .. tostring(idx)
    end
end

-- 第二阶段：原子批量锁座 + 扣库存 + 写回查凭证 + 入悬挂扫描索引
for i = 7, #ARGV do
    redis.call('SETBIT', KEYS[6], tonumber(ARGV[i]), 1)
end

redis.call('DECRBY', KEYS[1], seatCount)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3],
    'userId', ARGV[1],
    'qty', tostring(seatCount),
    'orderNo', ARGV[2],
    'tierId', ARGV[3],
    'zoneId', ARGV[4],
    'seatIndices', seatsStr,
    'ts', ARGV[5]
)
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[6]))
redis.call('ZADD', KEYS[4], tonumber(ARGV[5]), ARGV[2])

return 1
