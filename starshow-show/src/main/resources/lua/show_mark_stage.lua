-- 后台生命周期异步推进 Redis 门禁阶段（READY -> OPEN，或任意 -> ENDED），绝不动已预热库存
-- KEYS[1] = starshow:show:ready:{showId}
-- ARGV[1] = expectedVersion
-- ARGV[2] = targetStage ('OPEN' 或 'ENDED')
-- ARGV[3] = nowMs
-- 返回值: 1 成功推进, 0 版本或前驱阶段不满足

local readyKey = KEYS[1]
local expectedVer = ARGV[1]
local targetStage = ARGV[2]
local nowMs = ARGV[3]

local curStage = redis.call('HGET', readyKey, 'stage')
local curVer = redis.call('HGET', readyKey, 'version')

if targetStage == 'OPEN' then
    if curVer == expectedVer and (curStage == 'READY' or curStage == 'OPEN') then
        redis.call('HSET', readyKey, 'stage', 'OPEN', 'updatedAtMs', nowMs)
        return 1
    end
    return 0
end

if targetStage == 'ENDED' then
    if (not curVer) or curVer == expectedVer then
        redis.call('HSET', readyKey, 'version', expectedVer, 'stage', 'ENDED', 'updatedAtMs', nowMs)
        return 1
    end
    return 0
end

return 0
