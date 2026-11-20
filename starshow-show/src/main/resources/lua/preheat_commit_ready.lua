-- 预热全量回读校验通过后原子发布 READY 门禁（并固化开售/停售毫秒时间戳）
-- KEYS[1] = starshow:show:ready:{showId}
-- ARGV[1] = version
-- ARGV[2] = executionId
-- ARGV[3] = saleStartEpochMs
-- ARGV[4] = saleEndEpochMs
-- ARGV[5] = nowMs
-- 返回值: 1 成功发布 READY, 0 校验失败

local readyKey = KEYS[1]
local expectedVer = ARGV[1]
local expectedExec = ARGV[2]

local stage = redis.call('HGET', readyKey, 'stage')
local curVer = redis.call('HGET', readyKey, 'version')
local curExec = redis.call('HGET', readyKey, 'executionId')

if stage ~= 'PREPARING' or curVer ~= expectedVer or curExec ~= expectedExec then
    return 0
end

redis.call('HSET', readyKey,
    'stage', 'READY',
    'saleStartEpochMs', ARGV[3],
    'saleEndEpochMs', ARGV[4],
    'updatedAtMs', ARGV[5]
)
return 1
