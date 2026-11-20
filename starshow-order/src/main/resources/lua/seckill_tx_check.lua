-- ============================================================================
-- V4 事务消息 Broker 原子回查与防悬挂立碑脚本 (seckill_tx_check.lua)
-- 所有 KEYS 共享 {showId} Hash Tag，零 CROSSSLOT
--
-- KEYS[1]: starshow:deduct_log:{showId}:<orderNo>     事务消息回查凭证 Hash
-- KEYS[2]: starshow:rollback_done:{showId}:<orderNo>  回补幂等/防悬挂墓碑
--
-- ARGV[1]: ttlSec (默认 86400)
--
-- 返回码：
--   1 : 预扣凭证已存在 -> COMMIT_MESSAGE
--   0 : 预扣凭证不存在，已原子写入防悬挂墓碑 -> ROLLBACK_MESSAGE
-- ============================================================================

local deductLogKey = KEYS[1]
local rollbackDoneKey = KEYS[2]
local ttlSec = tonumber(ARGV[1]) or 86400

if redis.call('EXISTS', deductLogKey) == 1 then
    return 1
end

redis.call('SET', rollbackDoneKey, '1', 'NX', 'EX', ttlSec)
return 0
