-- like_toggle.lua (v1)
-- KEYS[1]=likersSet, KEYS[2]=countKey, KEYS[3]=streamKey, KEYS[4]=idempKey
-- ARGV[1]=userId, ARGV[2]=feedId, ARGV[3]=reqId, ARGV[4]=nowMillis
-- return: { nowOn(0/1), delta(-1/0/+1), newCount }
-- EXPIRE는 초 단위, PEXPIRE는 밀리초 단위

if redis.call('SETNX', KEYS[4], ARGV[4]) == 0 then
  local isMember = redis.call('SISMEMBER', KEYS[1], ARGV[1])
  local cnt = tonumber(redis.call('GET', KEYS[2]) or '0')
  return { isMember, 0, cnt }
end
redis.call('PEXPIRE', KEYS[4], 86400000)

local isMember = redis.call('SISMEMBER', KEYS[1], ARGV[1])
local delta = 0
local op = 'OFF'
if isMember == 1 then
  redis.call('SREM', KEYS[1], ARGV[1]); delta = -1; op = 'OFF'
else
  redis.call('SADD', KEYS[1], ARGV[1]); delta = 1; op = 'ON'
end

local newCnt = tonumber(redis.call('INCRBY', KEYS[2], delta))
redis.call('XADD', KEYS[3], '*',
  'feedId', ARGV[2], 'userId', ARGV[1],
  'op', op, 'delta', tostring(delta),
  'reqId', ARGV[3], 'ts', ARGV[4]
)
return { 1 - isMember, delta, newCnt }
