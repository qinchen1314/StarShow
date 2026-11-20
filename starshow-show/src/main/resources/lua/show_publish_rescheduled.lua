-- 改期 DB CAS 成功后发布新版本 INIT 状态与最新开售时间窗口
-- KEYS[1] = starshow:show:ready:{showId}
-- ARGV[1] = newVersion
-- ARGV[2] = saleStartEpochMs
-- ARGV[3] = saleEndEpochMs
-- ARGV[4] = nowMs
-- 返回值: 1 成功, 0 已存在更高版本(不回退)

local readyKey = KEYS[1]
local newVer = tonumber(ARGV[1])
local curVerStr = redis.call('HGET', readyKey, 'version')

if curVerStr then
    local curVer = tonumber(curVerStr)
    if curVer and curVer > newVer then
        return 0
    end
end

redis.call('HSET', readyKey,
    'version', tostring(newVer),
    'executionId', '',
    'stage', 'INIT',
    'saleStartEpochMs', ARGV[2],
    'saleEndEpochMs', ARGV[3],
    'updatedAtMs', ARGV[4]
)
return 1
