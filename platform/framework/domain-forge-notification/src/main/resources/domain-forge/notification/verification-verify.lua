-- 校验验证码：0 不存在或已过期，1 正确（随即删除），2 不正确，3 错误次数已达上限（含本次）。
-- KEYS[1] 验证码；ARGV[1] 摘要 ARGV[2] 最多校验次数
if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
local limit = tonumber(ARGV[2])
if tonumber(redis.call('HGET', KEYS[1], 'attempts')) >= limit then return 3 end
if redis.call('HGET', KEYS[1], 'digest') == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
if redis.call('HINCRBY', KEYS[1], 'attempts', 1) >= limit then return 3 end
return 2
