-- 按客户端地址的令牌桶。KEYS[1] 桶；ARGV[1] 每秒补充令牌数；ARGV[2] 桶容量。
-- 用 Redis 服务器时间，避免多个网关实例的时钟差影响补充量。返回 1 放行、0 超限。
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
local tokens = tonumber(state[1])
local at = tonumber(state[2])
if tokens == nil or at == nil then
  tokens = capacity
  at = now
end
tokens = math.min(capacity, tokens + math.max(0, now - at) * rate / 1000)
local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)
return allowed
