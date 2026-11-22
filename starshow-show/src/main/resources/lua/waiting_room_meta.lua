-- KEYS[1]: current show projection. ARGV: operation, version, start, end, seed, fence.
local key = KEYS[1]
local action = ARGV[1]
local version = tonumber(ARGV[2])
local startAt = tonumber(ARGV[3])
local endAt = tonumber(ARGV[4])
local current = tonumber(redis.call('HGET', key, 'version') or '0')
local fence = redis.call('HGET', key, 'fence') or ''

if current > version then
    return 'STALE'
end
if action == 'PAUSE' then
    local clock = redis.call('TIME')
    local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
    if now >= startAt then
        return 'STARTED'
    end
elseif current == version and fence ~= '' and ARGV[6] ~= fence then
    return 'PAUSED'
end

if current < version then
    redis.call('HSET', key, 'version', ARGV[2], 'startAt', ARGV[3], 'endAt', ARGV[4], 'seed', ARGV[5])
    redis.call('PEXPIREAT', key, endAt + 86400000)
end
redis.call('HSET', key, 'fence', action == 'PAUSE' and ARGV[6] or '')
return 'OK'
