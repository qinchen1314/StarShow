-- ============================================================================
-- 通用原子幂等回补脚本 (seckill_rollback.lua)
-- 兼容自动配座与自主选座模式，防止重复回补库存造成超卖
--
-- KEYS[1]: starshow:stock:{showId}:<tierId>           票档实时库存
-- KEYS[2]: starshow:bought:{showId}                   场次已购用户 Set
-- KEYS[3]: starshow:rollback_done:{showId}:<orderNo>  回补幂等标记
-- KEYS[4]: starshow:deduct_pending:{showId}           悬挂扣减扫描 ZSET
-- KEYS[5]: starshow:seat:bitmap:{showId}:<zoneId>     分区座位图 Bitmap（无选座时可传占位Key）
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

if redis.call('SET', KEYS[3], '1', 'NX', 'EX', tonumber(ARGV[4])) == false then
    return 0
end

local qty = tonumber(ARGV[2])
if qty and qty > 0 then
    redis.call('INCRBY', KEYS[1], qty)
end

redis.call('SREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[3])

-- 如有指定座位偏移，批量释放 Bitmap
if #ARGV >= 5 then
    for i = 5, #ARGV do
        local idx = tonumber(ARGV[i])
        if idx and idx >= 0 then
            redis.call('SETBIT', KEYS[5], idx, 0)
        end
    end
end

return 1
