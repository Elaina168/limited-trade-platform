-- 订单在 MySQL 提交后，更新请求状态为 CREATED 并移除在途集合条目。
redis.replicate_commands()
local userId = ARGV[1]
local requestId = ARGV[2]
local orderId = ARGV[3]
local ttlMs = tonumber(ARGV[4])

local existing = redis.call('GET', KEYS[1])
if existing then
    local sep = string.find(existing, '#', 1, true)
    if sep then
        local savedFp = string.sub(existing, 1, sep - 1)
        redis.call('SET', KEYS[1], savedFp .. '#CREATED:' .. orderId, 'PX', ttlMs)
    end
end
redis.call('HDEL', KEYS[2], userId .. ':' .. requestId)
return 1
