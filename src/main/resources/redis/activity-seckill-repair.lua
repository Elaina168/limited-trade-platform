-- 仅在计数仍为已核对的快照且没有在途请求时校准，保护其间新进入的抢购。
redis.replicate_commands()
if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('HLEN', KEYS[3]) ~= 0
        or (redis.call('GET', KEYS[2]) or '') ~= ARGV[1]
        or redis.call('HLEN', KEYS[4]) ~= tonumber(ARGV[2]) then
    return 0
end

local idx = 3
for i = 1, tonumber(ARGV[2]) do
    if redis.call('HGET', KEYS[4], ARGV[idx]) ~= ARGV[idx + 1] then
        return 0
    end
    idx = idx + 2
end

local ttlMs = redis.call('PTTL', KEYS[1])
redis.call('SET', KEYS[2], ARGV[idx])
redis.call('DEL', KEYS[4])
for i = idx + 1, #ARGV, 2 do
    redis.call('HSET', KEYS[4], ARGV[i], ARGV[i + 1])
end
if ttlMs >= 0 then
    redis.call('PEXPIRE', KEYS[2], ttlMs)
    redis.call('PEXPIRE', KEYS[4], ttlMs)
end
return 1
