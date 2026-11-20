-- ============================================================================
-- 自动配座模式：原子预扣脚本 (seckill_deduct.lua)
-- 所有 KEYS 共享 {showId} Hash Tag，100% 兼容 Redis Cluster，杜绝 CROSSSLOT
--
-- KEYS[1]: starshow:stock:{showId}:<tierId>           票档实时库存
-- KEYS[2]: starshow:bought:{showId}                   场次已购用户 Set
-- KEYS[3]: starshow:deduct_log:{showId}:<orderNo>     事务消息回查凭证 Hash
-- KEYS[4]: starshow:deduct_pending:{showId}           悬挂扣减扫描 ZSET
-- KEYS[5]: starshow:rollback_done:{showId}:<orderNo>  回补幂等/防悬挂墓碑
--
-- ARGV[1]: userId
-- ARGV[2]: qty
-- ARGV[3]: orderNo
-- ARGV[4]: tierId
-- ARGV[5]: nowEpochSec
-- ARGV[6]: ttlSec (默认 86400)
--
-- 返回码：
--   1 : 扣减成功
--  -1 : 库存不足或未预热
--  -2 : 用户重复抢购该场次
--  -3 : 命中防悬挂墓碑（该单已被回查回滚，拒绝延迟扣减）
-- ============================================================================

if redis.call('EXISTS', KEYS[5]) == 1 then
    return -3
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end

local stock = tonumber(redis.call('GET', KEYS[1]))
local qty = tonumber(ARGV[2])
if (not stock) or (not qty) or (qty <= 0) or (stock < qty) then
    return -1
end

redis.call('DECRBY', KEYS[1], qty)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3],
    'userId', ARGV[1],
    'qty', ARGV[2],
    'orderNo', ARGV[3],
    'tierId', ARGV[4],
    'ts', ARGV[5]
)
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[6]))
redis.call('ZADD', KEYS[4], tonumber(ARGV[5]), ARGV[3])

return 1
