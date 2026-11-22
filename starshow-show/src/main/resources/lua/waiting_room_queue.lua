-- KEYS: meta, generation state, accounts, identities, records, pre, post, join limit.
-- ARGV: action, expected version, user ID, expected queue ID, proposed queue ID, identity key, shuffle digest, join limit.
local action = ARGV[1]
local user = ARGV[3]
local projection = redis.call('HMGET', KEYS[1], 'version', 'startAt', 'endAt', 'fence')
local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local function result(status, record, retry, rank)
    return {status, projection[1] or '', projection[2] or '', projection[3] or '',
        string.format('%.0f', now), record or '', retry or 0, rank or 0}
end

if not projection[1] or (projection[4] and projection[4] ~= '') then
    return result('UNAVAILABLE')
end
if projection[1] ~= ARGV[2] then
    return result('VERSION_CHANGED')
end
local startAt = tonumber(projection[2])
local endAt = tonumber(projection[3])
if now >= endAt then
    return result('ENDED')
end
local expiresAt = endAt + 86400000

if action == 'START' then
    local attempts = redis.call('INCR', KEYS[8])
    if attempts == 1 then
        redis.call('PEXPIRE', KEYS[8], 60000)
    end
    if attempts > tonumber(ARGV[8]) then
        return result('RATE_LIMITED', nil, math.max(1, redis.call('PTTL', KEYS[8])))
    end
end

if (action == 'JOIN' or action == 'SEAL') and now >= startAt
        and redis.call('HSETNX', KEYS[2], 'preCount', redis.call('ZCARD', KEYS[6])) == 1 then
    redis.call('PEXPIREAT', KEYS[2], expiresAt)
end
if action == 'SEAL' then
    return result('WAITING')
end

local existing = redis.call('HGET', KEYS[3], user)
if existing then
    if ARGV[4] ~= '' and ARGV[4] ~= existing then
        return result('EXPIRED')
    end
    local record = redis.call('HGET', KEYS[5], existing)
    if not record then
        return result('UNAVAILABLE')
    end
    if action == 'RANK' then
        local seat = cjson.decode(record)
        local preCount = redis.call('HGET', KEYS[2], 'preCount')
        if not preCount then
            return result('WAITING', record)
        end
        local index = redis.call('ZRANK', seat.phase == 'PRE' and KEYS[6] or KEYS[7], seat.rankKey)
        if not index then
            return result('UNAVAILABLE')
        end
        return result('WAITING', record, 0, index + 1 + (seat.phase == 'POST' and tonumber(preCount) or 0))
    end
    return result('WAITING', record)
end
if action == 'READ' or action == 'RANK' then
    return result('EXPIRED')
end
if action == 'START' then
    return result('NEEDS_IDENTITY')
end

local queueId = ARGV[5]
local identity = ARGV[6]
if redis.call('HEXISTS', KEYS[4], identity) == 1 then
    return result('IDENTITY_CONFLICT')
end
local previous = tonumber(redis.call('HGET', KEYS[2], 'sequence') or '0')
if previous >= 9007199254740991 or redis.call('HEXISTS', KEYS[5], queueId) == 1 then
    return result('UNAVAILABLE')
end

local sequence = redis.call('HINCRBY', KEYS[2], 'sequence', 1)
local phase = now < startAt and redis.call('HEXISTS', KEYS[2], 'preCount') == 0 and 'PRE' or 'POST'
local rankKey = queueId
if phase == 'PRE' then
    rankKey = ARGV[7] .. ':' .. string.format('%016.0f', sequence) .. ':' .. queueId
    redis.call('ZADD', KEYS[6], 0, rankKey)
else
    redis.call('ZADD', KEYS[7], sequence, rankKey)
end
local record = cjson.encode({
    queueId = queueId, userId = user, identityKey = identity, phase = phase, rankKey = rankKey
})
redis.call('HSET', KEYS[3], user, queueId)
redis.call('HSET', KEYS[4], identity, queueId)
redis.call('HSET', KEYS[5], queueId, record)
for i = 2, 7 do
    redis.call('PEXPIREAT', KEYS[i], expiresAt)
end
return result('WAITING', record)
