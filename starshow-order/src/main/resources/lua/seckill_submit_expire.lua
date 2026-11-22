-- 0 = 留待复核或租约已续领，1 = 已预扣，2 = 未预扣且迟到执行已被封堵。
local pending, current, proof, owners, tombstone, bought = unpack(KEYS)
local order, user, lease, cutoff, ttl = unpack(ARGV)
local score = tonumber(redis.call('ZSCORE', pending, order))
if not score or score > tonumber(cutoff) then
    return 0
end
if redis.call('HGET', current, 'orderNo') == order then
    if redis.call('HGET', current, 'leaseVersion') ~= lease then
        return 0
    end
end
if redis.call('EXISTS', proof) == 1 then
    return 1
end
local owner = redis.call('HGET', owners, user)
if owner == order or (not owner and redis.call('SISMEMBER', bought, user) == 1)
    or (redis.call('HGET', current, 'orderNo') == order
        and redis.call('HGET', current, 'status') == 'PENDING_CONFIRM') then
    return 0
end

redis.call('SET', tombstone, '1', 'NX', 'EX', ttl)
if redis.call('HGET', current, 'orderNo') == order then
    redis.call('HSET', current, 'status', 'EXPIRING')
end
return 2
