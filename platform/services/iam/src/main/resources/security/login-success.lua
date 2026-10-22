-- 只删除成功请求自己的预占，不撤销其他并发失败。
local marker=redis.call('GET',KEYS[1])
local run=string.match(redis.call('INFO','server'),'run_id:(%x+)')
if not marker or not run then return -1 end
local nonce=string.match(marker,':(.+)$')
if not nonce or run..':'..nonce~=ARGV[1] then return -1 end
return redis.call('ZREM',KEYS[2],ARGV[2])
