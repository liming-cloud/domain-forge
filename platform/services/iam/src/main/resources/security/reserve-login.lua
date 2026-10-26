-- 原子预占：先清理窗口外的尝试，再判断地址与账号额度；超限返回剩余等待秒数，成功返回 0。
-- KEYS[1] 账号计数键，KEYS[2] 地址计数键；ARGV: 尝试 ID、账号次数、账号窗口秒、地址次数、地址窗口秒。
local time=redis.call('TIME')
local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
local accountWindow=tonumber(ARGV[3])*1000
local addressWindow=tonumber(ARGV[5])*1000
-- 剩余窗口来自原子操作内同一次Redis时间，不能用完整窗口伪报剩余等待。
local function remaining(key,window)
  local oldest=redis.call('ZRANGE',key,0,0,'WITHSCORES')
  return math.max(1,math.ceil((tonumber(oldest[2])+window-now)/1000))
end
redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-addressWindow)
if redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[4]) then return remaining(KEYS[2],addressWindow) end
redis.call('ZADD',KEYS[2],now,ARGV[1])
redis.call('PEXPIRE',KEYS[2],addressWindow)
redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-accountWindow)
if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return remaining(KEYS[1],accountWindow) end
redis.call('ZADD',KEYS[1],now,ARGV[1])
redis.call('PEXPIRE',KEYS[1],accountWindow)
return 0
