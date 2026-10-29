-- 保存首次结果：只有本次开始的处理（状态为 processing 且指纹相同）才能写入。
-- KEYS[1] 记录；ARGV[1] 指纹 ARGV[2] 状态码 ARGV[3] 内容类型 ARGV[4] 响应体（Base64） ARGV[5] 保留毫秒
if redis.call('HGET', KEYS[1], 'state') ~= 'processing' or redis.call('HGET', KEYS[1], 'fingerprint') ~= ARGV[1] then
    return 0
end
redis.call('HSET', KEYS[1], 'state', 'done', 'status', ARGV[2], 'type', ARGV[3], 'body', ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return 1
