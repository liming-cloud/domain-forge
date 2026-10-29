-- 放弃本次处理（服务端失败或结果过大），允许客户端用同一键重试。KEYS[1] 记录；ARGV[1] 指纹
if redis.call('HGET', KEYS[1], 'state') == 'processing' and redis.call('HGET', KEYS[1], 'fingerprint') == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
