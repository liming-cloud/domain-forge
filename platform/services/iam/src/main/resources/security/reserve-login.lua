-- 同一脚本验证代次并预占，不能在校验后FLUSHALL绕过失败窗口。
local marker=redis.call('GET',KEYS[1])
local server=redis.call('INFO','server')
local run=string.match(server,'run_id:(%x+)')
if not marker or not run then return -1 end
local nonce=string.match(marker,':(.+)$')
if not nonce or run..':'..nonce~=ARGV[1] then return -1 end
local time=redis.call('TIME')
local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
local accountWindow=tonumber(ARGV[4])*1000
local addressWindow=tonumber(ARGV[6])*1000
-- 剩余窗口来自原子操作内同一次Redis时间，不能用完整窗口伪报剩余等待。
local function remaining(key,window)
  local oldest=redis.call('ZRANGE',key,0,0,'WITHSCORES')
  return math.max(1,math.ceil((tonumber(oldest[2])+window-now)/1000))
end
redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',now-addressWindow)
if redis.call('ZCARD',KEYS[3])>=tonumber(ARGV[5]) then return remaining(KEYS[3],addressWindow) end
redis.call('ZADD',KEYS[3],now,ARGV[2])
redis.call('PEXPIRE',KEYS[3],addressWindow)
redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-accountWindow)
if redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[3]) then return remaining(KEYS[2],accountWindow) end
redis.call('ZADD',KEYS[2],now,ARGV[2])
redis.call('PEXPIRE',KEYS[2],accountWindow)
return 0
