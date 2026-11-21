-- All keys share {showId}. 1 = released, 0 = verified replay, -1 = unproven.
-- A transaction rollback tombstone alone is never a release receipt.
local stock, bought, tombstone, pending, bitmap, owners, receipt = unpack(KEYS)
local user, quantity, order, ttl = ARGV[1], tonumber(ARGV[2]), ARGV[3], tonumber(ARGV[4])

local function kind(key)
    return redis.call('TYPE', key).ok
end

if kind(receipt) == 'string' then
    if redis.call('GET', receipt) == order .. ':' .. ARGV[2] then return 0 end
    return -1
end
if kind(receipt) ~= 'none' or kind(tombstone) ~= 'none' then return -1 end
if kind(stock) ~= 'string' or kind(owners) ~= 'hash' or kind(bought) ~= 'set' then return -1 end
local pendingKind = kind(pending)
if pendingKind ~= 'none' and pendingKind ~= 'zset' then return -1 end
if redis.call('HGET', owners, user) ~= order or redis.call('SISMEMBER', bought, user) ~= 1 then return -1 end
local balanceText = redis.call('GET', stock)
local balance = tonumber(balanceText)
if not balanceText:match('^%d+$') or not balance or not quantity or quantity < 1 or quantity > 2147483647
   or quantity ~= math.floor(quantity) or balance > 2147483647 - quantity
   or not ttl or ttl < 1 or ttl > 31536000 or ttl ~= math.floor(ttl) then return -1 end

if #ARGV > 4 then
    if kind(bitmap) ~= 'string' or #ARGV - 4 ~= quantity then return -1 end
    local maxIndex = redis.call('STRLEN', bitmap) * 8 - 1
    local seen = {}
    for i = 5, #ARGV do
        local index = tonumber(ARGV[i])
        if not index or index < 0 or index > maxIndex or index ~= math.floor(index) or seen[index]
           or redis.call('GETBIT', bitmap, index) ~= 1 then return -1 end
        seen[index] = true
    end
end

redis.call('INCRBY', stock, quantity)
for i = 5, #ARGV do redis.call('SETBIT', bitmap, tonumber(ARGV[i]), 0) end
redis.call('HDEL', owners, user)
redis.call('SREM', bought, user)
redis.call('ZREM', pending, order)
redis.call('SET', tombstone, '1', 'EX', ttl)
redis.call('SET', receipt, order .. ':' .. ARGV[2], 'EX', ttl)
return 1
