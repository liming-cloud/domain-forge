-- 处理期间续租：只有仍在处理的本次请求（状态为 processing 且指纹相同）才延长租期。KEYS[1] 记录；ARGV[1] 指纹 ARGV[2] 租期毫秒
if redis.call('HGET', KEYS[1], 'state') == 'processing' and redis.call('HGET', KEYS[1], 'fingerprint') == ARGV[1] then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return 1
end
return 0
