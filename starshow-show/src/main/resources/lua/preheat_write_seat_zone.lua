-- 预热阶段带 Fencing Token 校验写入分区座位 Bitmap 与布局元数据
-- KEYS[1] = starshow:show:ready:{showId}
-- KEYS[2] = starshow:seat:bitmap:{showId}:<zoneId>
-- KEYS[3] = starshow:seat:layout:{showId}:<zoneId>
-- ARGV[1] = version
-- ARGV[2] = executionId
-- ARGV[3] = bitmapHex (十六进制编码的原始字节流，MSB最高位优先)
-- ARGV[4] = layoutJson
-- 返回值: 1 成功写入, 0 校验失败(0覆盖)

local readyKey = KEYS[1]
local expectedVer = ARGV[1]
local expectedExec = ARGV[2]

local stage = redis.call('HGET', readyKey, 'stage')
local curVer = redis.call('HGET', readyKey, 'version')
local curExec = redis.call('HGET', readyKey, 'executionId')

if stage ~= 'PREPARING' or curVer ~= expectedVer or curExec ~= expectedExec then
    return 0
end

local hex = ARGV[3]
local rawBytes = hex:gsub('..', function(cc)
    return string.char(tonumber(cc, 16))
end)

redis.call('SET', KEYS[2], rawBytes)
redis.call('SET', KEYS[3], ARGV[4])
return 1
