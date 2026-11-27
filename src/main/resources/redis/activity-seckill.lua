-- 原子完成：请求幂等校验、活动时间窗校验、一人一单/限购校验、剩余名额预扣与在途登记。
redis.replicate_commands()
local time = redis.call('TIME')
local nowMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local userId = ARGV[1]
local productId = tonumber(ARGV[2])
local qty = tonumber(ARGV[3])
local fingerprint = ARGV[4]
local inflightJson = ARGV[5]
local reqTtlMs = tonumber(ARGV[6])
local requestId = ARGV[7]

local existing = redis.call('GET', KEYS[4])
if existing then
    local sep = string.find(existing, '#', 1, true)
    if not sep then
        return {'CONFLICT', '购买幂等状态异常'}
    end
    local savedFp = string.sub(existing, 1, sep - 1)
    local statePart = string.sub(existing, sep + 1)
    if savedFp ~= fingerprint then
        return {'CONFLICT', '请求标识已用于另一笔不同参数的购买'}
    end
    if statePart == 'CANCELED' then
        return {'CONFLICT', '原购买请求对应的订单已取消，请重新提交'}
    end
    return {'REPLAY', statePart}
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {'UNINITIALIZED', ''}
end

local meta = redis.call('HMGET', KEYS[1], 'productId', 'startsAtMs', 'endsAtMs', 'perUserLimit')
local metaProductId = tonumber(meta[1])
local startsAtMs = tonumber(meta[2])
local endsAtMs = tonumber(meta[3])
local perUserLimit = tonumber(meta[4] or '0')

if metaProductId ~= productId then
    return {'PRODUCT_MISMATCH', '活动与商品不匹配'}
end
if nowMs < startsAtMs then
    return {'NOT_STARTED', '活动尚未开始'}
end
if nowMs >= endsAtMs then
    return {'ENDED', '活动已结束'}
end

if perUserLimit > 0 then
    local bought = tonumber(redis.call('HGET', KEYS[3], userId) or '0')
    if bought + qty > perUserLimit then
        return {'LIMIT_EXCEEDED', '已达到本活动每人限购上限'}
    end
end

local quota = tonumber(redis.call('GET', KEYS[2]) or '0')
if quota < qty then
    return {'SOLD_OUT', '本活动名额已抢完'}
end

redis.call('DECRBY', KEYS[2], qty)
redis.call('HINCRBY', KEYS[3], userId, qty)
redis.call('SET', KEYS[4], fingerprint .. '#QUEUED', 'PX', reqTtlMs)
redis.call('HSET', KEYS[5], userId .. ':' .. requestId, nowMs .. '|' .. inflightJson)
return {'QUEUED', tostring(nowMs)}
