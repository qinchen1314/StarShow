-- 开始或抢占演出预热租约（Fencing Token）
-- KEYS[1] = starshow:show:ready:{showId}
-- ARGV[1] = version (saleConfigVersion)
-- ARGV[2] = executionId (Fencing Token)
-- ARGV[3] = saleStartEpochMs
-- ARGV[4] = saleEndEpochMs
-- ARGV[5] = nowMs
-- 返回值:
--   1  = 成功进入 PREPARING 并获得 executionId 写租约
--   2  = 同版本已 READY 且尚未进入开售窗口（幂等成功）
--  -1  = 场次已 OPEN / ENDED 或已进入开售窗口，严禁覆盖在售库存
--  -2  = 已存在更高配置版本，旧版本任务禁止覆盖

local key = KEYS[1]
local reqVer = tonumber(ARGV[1])
local execId = ARGV[2]
local startMs = tonumber(ARGV[3])
local endMs = tonumber(ARGV[4])
local nowMs = tonumber(ARGV[5])

local curStage = redis.call('HGET', key, 'stage')
local curVerStr = redis.call('HGET', key, 'version')
local curStartStr = redis.call('HGET', key, 'saleStartEpochMs')

if curStage == 'OPEN' or curStage == 'ENDED' then
    return -1
end

if curVerStr then
    local curVer = tonumber(curVerStr)
    if curVer and curVer > reqVer then
        return -2
    end
    if curVer and curVer == reqVer and curStage == 'READY' then
        local curStart = tonumber(curStartStr or '0')
        if curStart > 0 and nowMs >= curStart then
            return -1
        end
        return 2
    end
end

redis.call('HSET', key,
    'version', tostring(reqVer),
    'executionId', execId,
    'stage', 'PREPARING',
    'saleStartEpochMs', tostring(startMs),
    'saleEndEpochMs', tostring(endMs),
    'updatedAtMs', tostring(nowMs)
)
return 1
