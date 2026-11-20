-- 预热阶段带 Fencing Token 校验分批写入票档实时库存
-- KEYS[1]    = starshow:show:ready:{showId}
-- KEYS[2..N] = starshow:stock:{showId}:<tierId>
-- ARGV[1]    = version
-- ARGV[2]    = executionId
-- ARGV[3..N+1] = 对应各票档初始库存
-- 返回值: 1 成功写入, 0 校验失败(失去租约/旧版本/已改期BLOCKED/已开售，0覆盖)

local readyKey = KEYS[1]
local expectedVer = ARGV[1]
local expectedExec = ARGV[2]

local stage = redis.call('HGET', readyKey, 'stage')
local curVer = redis.call('HGET', readyKey, 'version')
local curExec = redis.call('HGET', readyKey, 'executionId')

if stage ~= 'PREPARING' or curVer ~= expectedVer or curExec ~= expectedExec then
    return 0
end

for i = 2, #KEYS do
    redis.call('SET', KEYS[i], ARGV[i + 1])
end
return 1
