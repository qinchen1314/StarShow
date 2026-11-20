-- ============================================================================
-- 两段式短 Lua 第一段：通用请求绑定与凭证校验脚本 (seckill_req_bind.lua)
-- V1~V4 全部复用，所有 KEYS 共享 {showId} Hash Tag，零 CROSSSLOT
--
-- KEYS[1]: starshow:seckill:req:{showId}:<userId>:<clientRequestId>  请求幂等绑定 Hash
-- KEYS[2]: starshow:seckill:attempt:{showId}:<candidateOrderNo>      在途尝试记录 Hash
-- KEYS[3]: starshow:token:{showId}:<userId>:<seckillToken>           短期抢票凭证 String (值=saleConfigVersion)
-- KEYS[4]: starshow:seckill:submit_pending:{showId}                  V4 待提交恢复索引 ZSET
--
-- ARGV[1]: candidateOrderNo
-- ARGV[2]: fingerprint (SHA-256)
-- ARGV[3]: userId
-- ARGV[4]: showId
-- ARGV[5]: tierId
-- ARGV[6]: qty
-- ARGV[7]: expectedSaleConfigVersion
-- ARGV[8]: nowEpochMs
-- ARGV[9]: reqTtlSec (默认 1800)
-- ARGV[10]: leaseMs (默认 15000)
-- ARGV[11]: strategy (V1/V2/V3/V4)
-- ARGV[12]: clientRequestId
--
-- 返回数组：{code, orderNo, status, failCode, leaseAcquired}
--   BOUND           : 首次绑定成功（使用 candidateOrderNo）
--   EXISTING        : 同请求重放且摘要一致（找回 storedOrderNo，不受 Token/窗口/价格变化影响）
--   CONFLICT        : 同请求重放但摘要不一致 -> 对外 A0307
--   TOKEN_INVALID   : 首次请求但 seckillToken 缺失或过期 -> 对外 A0303
--   VERSION_MISMATCH: 首次请求但 seckillToken 对应配置版本已过期 -> 对外 A0205
-- ============================================================================

local reqKey = KEYS[1]
local attemptKey = KEYS[2]
local tokenKey = KEYS[3]
local submitPendingKey = KEYS[4]

local candidateOrderNo = ARGV[1]
local fingerprint = ARGV[2]
local userId = ARGV[3]
local showId = ARGV[4]
local tierId = ARGV[5]
local qty = ARGV[6]
local expectedVersion = ARGV[7]
local nowMs = tonumber(ARGV[8])
local reqTtlSec = tonumber(ARGV[9])
local leaseMs = tonumber(ARGV[10])
local strategy = ARGV[11]
local clientRequestId = ARGV[12]

-- 1. 已存在同请求绑定：先核对参数摘要（已有绑定恢复不受后续 Token 失效/票价变化/窗口变化影响）
if redis.call('EXISTS', reqKey) == 1 then
    local storedFp = redis.call('HGET', reqKey, 'fingerprint')
    local storedOrderNo = redis.call('HGET', reqKey, 'orderNo') or ''
    local storedStatus = redis.call('HGET', reqKey, 'status') or 'SUBMITTING'
    local storedFailCode = redis.call('HGET', reqKey, 'failCode') or ''

    if storedFp ~= fingerprint then
        return {'CONFLICT', storedOrderNo, storedStatus, storedFailCode, '0'}
    end

    local leaseAcquired = '0'
    if storedStatus == 'SUBMITTING' then
        local leaseUntil = tonumber(redis.call('HGET', reqKey, 'leaseUntilMs') or '0')
        if leaseUntil <= nowMs then
            local newLease = tostring(nowMs + leaseMs)
            redis.call('HSET', reqKey, 'leaseUntilMs', newLease, 'updatedAtMs', tostring(nowMs))
            leaseAcquired = '1'
        end
    end
    return {'EXISTING', storedOrderNo, storedStatus, storedFailCode, leaseAcquired}
end

-- 2. 首次请求：校验短期 seckillToken（先验不立刻删，确保售罄/冲突快速失败时不浪费同场次重试凭证）
local tokenVer = redis.call('GET', tokenKey)
if not tokenVer then
    return {'TOKEN_INVALID', '', '', '', '0'}
end
if tokenVer ~= expectedVersion then
    return {'VERSION_MISMATCH', '', '', '', '0'}
end

-- 3. 原子绑定 (userId, showId, clientRequestId) -> candidateOrderNo 与 attempt 在途记录
local leaseUntilStr = tostring(nowMs + leaseMs)
local nowMsStr = tostring(nowMs)

redis.call('HSET', reqKey,
    'orderNo', candidateOrderNo,
    'fingerprint', fingerprint,
    'userId', userId,
    'showId', showId,
    'tierId', tierId,
    'qty', qty,
    'strategy', strategy,
    'status', 'SUBMITTING',
    'failCode', '',
    'createdAtMs', nowMsStr,
    'updatedAtMs', nowMsStr,
    'leaseUntilMs', leaseUntilStr
)
redis.call('EXPIRE', reqKey, reqTtlSec)

redis.call('HSET', attemptKey,
    'orderNo', candidateOrderNo,
    'userId', userId,
    'showId', showId,
    'tierId', tierId,
    'qty', qty,
    'clientRequestId', clientRequestId,
    'fingerprint', fingerprint,
    'strategy', strategy,
    'status', 'SUBMITTING',
    'failCode', '',
    'retryable', '0',
    'createdAtMs', nowMsStr,
    'updatedAtMs', nowMsStr,
    'leaseUntilMs', leaseUntilStr
)
redis.call('EXPIRE', attemptKey, reqTtlSec)

if strategy == 'V4' then
    redis.call('ZADD', submitPendingKey, nowMs + leaseMs, candidateOrderNo)
end

return {'BOUND', candidateOrderNo, 'SUBMITTING', '', '1'}
