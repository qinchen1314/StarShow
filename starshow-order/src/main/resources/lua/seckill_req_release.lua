-- ============================================================================
-- 快速失败释放未生效请求绑定脚本 (seckill_req_release.lua)
-- 仅当请求仍处于 SUBMITTING 且 orderNo 匹配时清理绑定，避免用户因售罄/座位冲突无法同场切换重试
-- 所有 KEYS 共享 {showId} Hash Tag，零 CROSSSLOT
--
-- KEYS[1]: starshow:seckill:req:{showId}:<userId>:<clientRequestId>
-- KEYS[2]: starshow:seckill:attempt:{showId}:<orderNo>
-- KEYS[3]: starshow:seckill:submit_pending:{showId}
-- KEYS[4]: starshow:seckill:current:{showId}:<userId>
-- KEYS[5]: starshow:deduct_log:{showId}:<orderNo>
--
-- ARGV[1]: orderNo
-- ARGV[2]: failCode (记录在 attempt 中用于观测，可选)
-- ARGV[3]: deleteReq ("1" 删除 reqKey 允许复用/换档重试; "0" 保留终态 FAILED)
-- ARGV[4]: retryable ("1" / "0")
-- ARGV[5]: nowEpochMs
-- ARGV[6]: ttlSec
-- ARGV[7]: leaseVersion (V4, V1~V3 为空)
-- ============================================================================

local reqKey = KEYS[1]
local attemptKey = KEYS[2]
local submitPendingKey = KEYS[3]
local currentKey = KEYS[4]

local orderNo = ARGV[1]
local failCode = ARGV[2]
local deleteReq = ARGV[3]
local retryable = ARGV[4]
local nowMs = ARGV[5]
local ttlSec = tonumber(ARGV[6])
local leaseVersion = ARGV[7]

if leaseVersion ~= '' then
    if redis.call('HGET', currentKey, 'orderNo') ~= orderNo
        or redis.call('HGET', currentKey, 'leaseVersion') ~= leaseVersion
        or redis.call('HGET', currentKey, 'status') ~= 'SUBMITTING'
        or redis.call('EXISTS', KEYS[5]) == 1 then
        return 0
    end
    redis.call('DEL', currentKey)
end

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
