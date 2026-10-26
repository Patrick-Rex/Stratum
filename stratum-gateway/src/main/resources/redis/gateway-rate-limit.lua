-- 四个固定键与有限IP字段共同构成共享桶；时间与回收都由Redis原子决定。
local identity = ARGV[1]
local rate = tonumber(ARGV[2])
local burst = tonumber(ARGV[3])
local maximum = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])
local policy = ARGV[6]
local observed = redis.call('GET', KEYS[4])
if observed and observed ~= policy then return {0, 0, 0, 3} end
if not observed and redis.call('ZCARD', KEYS[3]) > 0 then return {0, 0, 0, 3} end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
-- 每次最多清理128个过期槽位，避免单次请求执行无界扫描。
local expired = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', now, 'LIMIT', 0, 128)
for _, client in ipairs(expired) do
    redis.call('HDEL', KEYS[1], client)
    redis.call('HDEL', KEYS[2], client)
    redis.call('ZREM', KEYS[3], client)
end
local population = redis.call('ZCARD', KEYS[3])
-- 任一键单独丢失或被驱逐时不能重置额度，也不能留下超出槽位预算的孤立字段。
if redis.call('HLEN', KEYS[1]) ~= population or redis.call('HLEN', KEYS[2]) ~= population then return {0, 0, 0, 3} end
local saved = redis.call('HGET', KEYS[1], identity)
local previous = redis.call('HGET', KEYS[2], identity)
local expiry = redis.call('ZSCORE', KEYS[3], identity)
if (saved and (not previous or not expiry)) or (not saved and (previous or expiry)) then return {0, 0, 0, 3} end
if not saved and population >= maximum then return {0, 0, 1000, 2} end
local tokens = burst
if saved then
    previous = tonumber(previous)
    if not previous then return {0, 0, 0, 3} end
    tokens = math.min(burst, tonumber(saved) + math.max(0, now - previous) * rate / 1000)
end
local allowed = 0
local retry = 0
local reason = 1
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
    reason = 0
else
    retry = math.ceil((1 - tokens) * 1000 / rate)
end
redis.call('HSET', KEYS[1], identity, tokens)
redis.call('HSET', KEYS[2], identity, now)
redis.call('ZADD', KEYS[3], now + ttl * 1000, identity)
redis.call('SET', KEYS[4], policy, 'EX', ttl)
for index = 1, 3 do redis.call('EXPIRE', KEYS[index], ttl) end
return {allowed, math.floor(tokens), retry, reason}
