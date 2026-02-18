-- wallet_gate_release.lua
-- 지갑 분산 락(Gate) 해제
-- 소유자가 일치할 때만 삭제 (다른 요청의 락을 실수로 해제하지 않음)
--
-- KEYS[1] = gate key (예: wallet:gate:{walletId})
-- ARGV[1] = owner    (락 소유자 식별자)
-- return: 1(해제 성공) / 0(소유자 불일치 또는 이미 만료)

if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
else
  return 0
end
