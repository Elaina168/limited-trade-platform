-- 初始化或校准活动的 Redis 秒杀元数据、剩余名额与已购用户哈希。
redis.replicate_commands()
local force = ARGV[1] == '1'
if not force and redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

local productId = ARGV[2]
local startsAtMs = ARGV[3]
local endsAtMs = ARGV[4]
local perUserLimit = ARGV[5]
local remainingQuota = ARGV[6]
local totalQuota = ARGV[7]
local ttlMs = tonumber(ARGV[8])

redis.call('HSET', KEYS[1],
    'productId', productId,
    'startsAtMs', startsAtMs,
    'endsAtMs', endsAtMs,
    'perUserLimit', perUserLimit,
    'totalQuota', totalQuota)
redis.call('SET', KEYS[2], remainingQuota)
redis.call('DEL', KEYS[3])

local idx = 9
while idx <= #ARGV do
    local uid = ARGV[idx]
    local qty = ARGV[idx + 1]
    if uid and qty and tonumber(qty) > 0 then
        redis.call('HSET', KEYS[3], uid, qty)
    end
    idx = idx + 2
end

if ttlMs and ttlMs > 0 then
    redis.call('PEXPIRE', KEYS[1], ttlMs)
    redis.call('PEXPIRE', KEYS[2], ttlMs)
    if redis.call('EXISTS', KEYS[3]) == 1 then
        redis.call('PEXPIRE', KEYS[3], ttlMs)
    end
end
return 1
