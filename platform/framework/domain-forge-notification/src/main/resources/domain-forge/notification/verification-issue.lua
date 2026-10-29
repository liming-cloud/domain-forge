-- 签发验证码：重发间隔内拒绝（-1），当天已达上限拒绝（-2）；否则计数、设置重发间隔、以新摘要覆盖旧验证码（1）。
-- KEYS[1] 验证码（hash：digest、attempts） KEYS[2] 重发间隔标记 KEYS[3] 当天发送计数
-- ARGV[1] 摘要 ARGV[2] 有效期毫秒 ARGV[3] 重发间隔毫秒 ARGV[4] 每天上限 ARGV[5] 计数保留毫秒
if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
local sent = tonumber(redis.call('GET', KEYS[3]) or '0')
if sent >= tonumber(ARGV[4]) then return -2 end
redis.call('INCR', KEYS[3])
if sent == 0 then redis.call('PEXPIRE', KEYS[3], ARGV[5]) end
redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'digest', ARGV[1], 'attempts', 0)
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
