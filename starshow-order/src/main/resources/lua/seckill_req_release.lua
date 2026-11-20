-- ============================================================================
-- 快速失败释放未生效请求绑定脚本 (seckill_req_release.lua)
-- 仅当请求仍处于 SUBMITTING 且 orderNo 匹配时清理绑定，避免用户因售罄/座位冲突无法同场切换重试
-- 所有 KEYS 共享 {showId} Hash Tag，零 CROSSSLOT
--
-- KEYS[1]: starshow:seckill:req:{showId}:<userId>:<clientRequestId>
-- KEYS[2]: starshow:seckill:attempt:{showId}:<orderNo>
-- KEYS[3]: starshow:seckill:submit_pending:{showId}
--
-- ARGV[1]: orderNo
-- ARGV[2]: failCode (记录在 attempt 中用于观测，可选)
-- ARGV[3]: deleteReq ("1" 删除 reqKey 允许复用/换档重试; "0" 保留终态 FAILED)
-- ARGV[4]: retryable ("1" / "0")
-- ARGV[5]: nowEpochMs
-- ARGV[6]: ttlSec
-- ============================================================================

local reqKey = KEYS[1]
local attemptKey = KEYS[2]
local submitPendingKey = KEYS[3]

local orderNo = ARGV[1]
local failCode = ARGV[2]
local deleteReq = ARGV[3]
local retryable = ARGV[4]
local nowMs = ARGV[5]
local ttlSec = tonumber(ARGV[6])

local curOrderNo = redis.call('HGET', reqKey, 'orderNo')
local curStatus = redis.call('HGET', reqKey, 'status')

if curOrderNo == orderNo and curStatus == 'SUBMITTING' then
    if deleteReq == '1' then
        redis.call('DEL', reqKey)
    else
        redis.call('HSET', reqKey, 'status', 'FAILED', 'failCode', failCode, 'updatedAtMs', nowMs)
        redis.call('EXPIRE', reqKey, ttlSec)
    end
end

if redis.call('EXISTS', attemptKey) == 1 then
    local attemptStatus = redis.call('HGET', attemptKey, 'status')
    if attemptStatus == 'SUBMITTING' then
        redis.call('HSET', attemptKey,
            'status', 'FAILED',
            'failCode', failCode,
            'retryable', retryable,
            'updatedAtMs', nowMs
        )
        redis.call('EXPIRE', attemptKey, ttlSec)
    end
end

redis.call('ZREM', submitPendingKey, orderNo)
return 1
