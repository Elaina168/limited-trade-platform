-- 一个用户共用一个键；首次请求同时建立计数和过期时间。
local current = redis.call('GET', KEYS[1])
if not current then
    redis.call('SET', KEYS[1], 1, 'PX', ARGV[2])
    return 0
end

-- 外部存储中的损坏键必须报错，不能变成永久额度或无条件放行。
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
    return redis.error_reply('Order rate limit key has no expiration')
end
local count = tonumber(current)
if not count or count < 1 or count % 1 ~= 0 then
    return redis.error_reply('Invalid order rate limit counter')
end

if count < tonumber(ARGV[1]) then
    redis.call('INCR', KEYS[1])
    return 0
end

-- 拒绝不递增、不续期；剩余0毫秒也至少等待1秒。
return math.floor(ttl / 1000) + 1
