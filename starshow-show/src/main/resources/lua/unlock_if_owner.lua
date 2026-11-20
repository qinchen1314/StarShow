-- 安全释放分布式锁（仅当持锁 token 匹配时删除）
-- KEYS[1] = lockKey
-- ARGV[1] = token
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
