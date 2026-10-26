-- 只删除成功请求自己的预占，不撤销其他并发失败。KEYS[1] 账号计数键，ARGV[1] 尝试 ID。
return redis.call('ZREM',KEYS[1],ARGV[1])
