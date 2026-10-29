-- 开始一次幂等请求。KEYS[1] 记录（hash：state、fingerprint、status、type、body）；ARGV[1] 请求指纹 ARGV[2] 处理租期毫秒
-- 返回 {'new'} 首次、{'mismatch'} 同一键但请求不同、{'processing'} 首次请求仍在处理、{'done', 状态码, 类型, 响应体} 已完成
local state = redis.call('HGET', KEYS[1], 'state')
if not state then
    redis.call('HSET', KEYS[1], 'state', 'processing', 'fingerprint', ARGV[1])
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return {'new'}
end
if redis.call('HGET', KEYS[1], 'fingerprint') ~= ARGV[1] then return {'mismatch'} end
if state == 'processing' then return {'processing'} end
local saved = redis.call('HMGET', KEYS[1], 'status', 'type', 'body')
return {'done', saved[1], saved[2], saved[3]}
