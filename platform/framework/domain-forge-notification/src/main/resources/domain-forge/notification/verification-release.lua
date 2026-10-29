-- 短信未发出时撤销本次签发：删除本次的验证码（摘要相同才删）与重发间隔，退还当天计数。
-- KEYS 同 verification-issue.lua；ARGV[1] 本次的摘要
if redis.call('HGET', KEYS[1], 'digest') == ARGV[1] then redis.call('DEL', KEYS[1]) end
redis.call('DEL', KEYS[2])
if tonumber(redis.call('GET', KEYS[3]) or '0') > 0 then redis.call('DECR', KEYS[3]) end
return 1
