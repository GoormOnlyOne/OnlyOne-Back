import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 각 서버로의 요청 카운터
const requestsTotal = new Counter('requests_total');

// 간단한 부하 분산 테스트
export const options = {
  scenarios: {
    load_balance_check: {
      executor: 'constant-vus',
      vus: 30,  // 30명의 가상 사용자
      duration: '1m',  // 1분간 테스트
      exec: 'loadBalanceTest',
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://nginx:80';

// tokens.json 파일에서 토큰 로드
const tokensFile = open('./tokens.json');
const userTokens = JSON.parse(tokensFile);

let testUsers = [];
for (const userId in userTokens) {
  testUsers.push({
    userId: parseInt(userId),
    token: userTokens[userId],
  });
}

console.log(`✅ ${testUsers.length}명의 사용자 토큰 로드 완료`);

export function setup() {
  return { testUsers };
}

function getAuthToken(userId) {
  const token = userTokens[userId];
  if (!token) {
    throw new Error(`토큰 없음 for user ${userId}`);
  }
  return token;
}

// 부하 분산 테스트
export function loadBalanceTest(data) {
  // 각 VU는 고정된 사용자를 사용 (ip_hash 테스트)
  const vuIndex = __VU % data.testUsers.length;
  const user = data.testUsers[vuIndex];
  const token = getAuthToken(user.userId);

  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };

  requestsTotal.add(1);

  // 헬스체크 엔드포인트로 요청 (가벼운 요청)
  const res = http.get(`${BASE_URL}/actuator/health`, params);

  check(res, {
    '상태 코드 200': (r) => r.status === 200,
    '응답 시간 < 1초': (r) => r.timings.duration < 1000,
  });

  if (res.status !== 200) {
    console.error(`요청 실패: VU=${__VU}, userId=${user.userId}, status=${res.status}`);
  }

  sleep(1);  // 1초 대기
}
