-- wallet_gate_acquire.lua
-- 지갑 동시접근 방지용 분산 락(Gate) 획득
-- SET NX + EX로 원자적 락 획득, 이미 잠겨있으면 0 반환
--
-- KEYS[1] = gate key (예: wallet:gate:{walletId})
-- ARGV[1] = ttlSec   (락 자동 만료 시간, 초)
-- ARGV[2] = owner    (락 소유자 식별자)
-- return: 1(획득 성공) / 0(이미 잠김)

local ok = redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[1], 'NX')
if ok then return 1 else return 0 end
