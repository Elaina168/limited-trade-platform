-- 建单失败、订单取消或超时关闭时，幂等归还 Redis 活动名额与本人已购计数。
redis.replicate_commands()
local userId = ARGV[1]
local requestId = ARGV[2]
local qty = tonumber(ARGV[3])
local totalCap = tonumber(ARGV[4])
local newState = ARGV[5]
local ttlMs = tonumber(ARGV[6])

if not redis.call('SET', KEYS[5], '1', 'NX', 'PX', ttlMs) then
    return 0
end

if redis.call('EXISTS', KEYS[1]) == 1 then
    local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
    local restored = cur + qty
    if totalCap > 0 and restored > totalCap then
        restored = totalCap
    end
    redis.call('SET', KEYS[1], tostring(restored))
end

if redis.call('EXISTS', KEYS[2]) == 1 then
    local bought = tonumber(redis.call('HGET', KEYS[2], userId) or '0')
    local nextBought = bought - qty
    if nextBought <= 0 then
        redis.call('HDEL', KEYS[2], userId)
    else
        redis.call('HSET', KEYS[2], userId, tostring(nextBought))
    end
end

local existing = redis.call('GET', KEYS[3])
if existing then
    local sep = string.find(existing, '#', 1, true)
    if sep then
        local savedFp = string.sub(existing, 1, sep - 1)
        redis.call('SET', KEYS[3], savedFp .. '#' .. newState, 'PX', ttlMs)
    end
end

redis.call('HDEL', KEYS[4], userId .. ':' .. requestId)
return 1
