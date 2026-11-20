-- 主办方安全改期前置门禁：原子校验未开售且版本匹配，将旧配置置为 BLOCKED
-- KEYS[1] = starshow:show:ready:{showId}
-- ARGV[1] = expectedVersion
-- ARGV[2] = nowMs
-- 返回值:
--   1  = 已原子置为 BLOCKED，允许继续执行 DB CAS 改期
--  -1  = 场次已 OPEN / ENDED 或已进入毫秒级开售窗口，严禁改期
--  -2  = 版本不匹配（并发改期或旧任务）

local readyKey = KEYS[1]
local expectedVer = tonumber(ARGV[1])
local nowMs = tonumber(ARGV[2])

local curStage = redis.call('HGET', readyKey, 'stage')
local curVerStr = redis.call('HGET', readyKey, 'version')
local curStartStr = redis.call('HGET', readyKey, 'saleStartEpochMs')

if not curStage then
    redis.call('HSET', readyKey,
        'version', tostring(expectedVer),
        'executionId', '',
        'stage', 'BLOCKED',
        'updatedAtMs', tostring(nowMs)
    )
    return 1
end

if curStage == 'OPEN' or curStage == 'ENDED' then
    return -1
end

local curVer = tonumber(curVerStr or '0')
if curVer ~= expectedVer then
    return -2
end

if curStage == 'READY' then
    local curStart = tonumber(curStartStr or '0')
    if curStart > 0 and nowMs >= curStart then
        return -1
    end
end

redis.call('HSET', readyKey,
    'stage', 'BLOCKED',
    'executionId', '',
    'updatedAtMs', tostring(nowMs)
)
return 1
