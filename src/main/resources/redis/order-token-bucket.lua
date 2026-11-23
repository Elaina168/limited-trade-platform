-- 使用 Redis 服务端时钟，并让 Redis 6 复制实际写入命令。
redis.replicate_commands()
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local refillPerMs = tonumber(ARGV[1])
local tokenCost = tonumber(ARGV[2])
local capacity = refillPerMs * tokenCost
local available = capacity
local last = now

local state = redis.call('GET', KEYS[1])
if state then
    local balance, timestamp = string.match(state, '^(%d+):(%d+)$')
    available = tonumber(balance)
    last = tonumber(timestamp)
    if not available or available > capacity or last >= 4503599627370496
            or redis.call('PTTL', KEYS[1]) < 0 then
        return redis.error_reply('Invalid order token bucket state')
    end
    -- 回拨时保留上次时间。满桶之后的空闲时间不会积累额度。
    local elapsed = math.min(tokenCost, math.max(0, now - last))
    available = math.min(capacity, available + elapsed * refillPerMs)
    last = math.max(now, last)
end

local clockDelay = last - now
if available < tokenCost then
    -- 拒绝既不扣减也不重设到期时间，向上取整成 Retry-After 秒数。
    local untilNext = clockDelay + math.ceil((tokenCost - available) / refillPerMs)
    return math.ceil(untilNext / 1000)
end

available = available - tokenCost
local ttl = clockDelay + math.ceil((capacity - available) / refillPerMs)
redis.call('SET', KEYS[1], string.format('%.0f:%.0f', available, last), 'PX', string.format('%.0f', ttl))
return 0
