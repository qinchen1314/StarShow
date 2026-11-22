-- 已预扣或已裁决的状态更新，与请求归属和状态推进同槽原子执行。
local attempt, req, current, pending = unpack(KEYS)
local order, user, show, status, fail, retryable, now, ttl = unpack(ARGV)

local existing = redis.call('HGET', attempt, 'status')
if status == 'PENDING_CONFIRM' and existing and existing ~= 'SUBMITTING' and existing ~= 'PENDING_CONFIRM' then
    return 0
end

redis.call('HSET', attempt, 'orderNo', order, 'userId', user, 'showId', show,
    'status', status, 'failCode', fail, 'retryable', retryable, 'updatedAtMs', now)
if redis.call('HGET', req, 'orderNo') == order then
    redis.call('HSET', req, 'status', status, 'failCode', fail, 'updatedAtMs', now)
end

if status == 'PENDING_CONFIRM' and redis.call('HGET', current, 'orderNo') == order then
    return 1
end
redis.call('EXPIRE', attempt, ttl)
if redis.call('HGET', req, 'orderNo') == order then
    redis.call('EXPIRE', req, ttl)
end
if redis.call('HGET', current, 'orderNo') == order then
    redis.call('DEL', current)
end
redis.call('ZREM', pending, order)
return 1
