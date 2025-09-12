-- KEYS[1] = gate key, ARGV[1] = ttlSec, ARGV[2] = owner
local ok = redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[1], 'NX')
if ok then return 1 else return 0 end
