-- 在同一个 Redis 执行步骤内读取各计数，避免半次预扣或释放混入快照。
return {redis.call('EXISTS', KEYS[1]), redis.call('GET', KEYS[2]) or '',
        redis.call('HGETALL', KEYS[3]), redis.call('HGETALL', KEYS[4])}
