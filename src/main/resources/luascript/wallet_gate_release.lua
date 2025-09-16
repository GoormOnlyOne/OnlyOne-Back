-- KEYS[1] = gate key, ARGV[1] = owner
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
else
  return 0
end
