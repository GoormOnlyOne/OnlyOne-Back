import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// 커스텀 메트릭
const apiRequestsTotal = new Counter('api_requests_total');
const apiRequestsFailed = new Counter('api_requests_failed');
const apiSuccessRate = new Rate('api_success_rate');
const apiResponseTime = new Trend('api_response_time');
const listQueryTime = new Trend('list_query_time');
const unreadCountTime = new Trend('unread_count_time');

// API 조회만 테스트
export const options = {
  scenarios: {
    api_queries: {
      executor: 'constant-arrival-rate',
      rate: 300,  // 초당 300개 API 요청 (3배 증가)
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: 'apiTest',
    },
  },

  thresholds: {
    'http_req_duration': ['p(95)<500', 'p(99)<1000'],  // 95% < 500ms, 99% < 1초
    'api_success_rate': ['rate>0.98'],  // 98% 성공률
    'http_req_failed': ['rate<0.02'],  // 2% 미만 실패
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

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

// API 조회 테스트
export function apiTest(data) {
  const user = data.testUsers[Math.floor(Math.random() * data.testUsers.length)];
  const token = getAuthToken(user.userId);

  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      'Pragma': 'no-cache',
    },
  };

  apiRequestsTotal.add(1);

  // 캐시 우회를 위한 랜덤 파라미터 추가
  const cacheBuster = Date.now() + Math.random();

  // 1. 알림 목록 조회
  const listRes = http.get(`${BASE_URL}/notifications?size=20&_=${cacheBuster}`, params);
  listQueryTime.add(listRes.timings.duration);
  apiResponseTime.add(listRes.timings.duration);

  const listSuccess = check(listRes, {
    '알림 목록 조회 성공': (r) => r.status === 200,
    '목록 응답 시간 < 500ms': (r) => r.timings.duration < 500,
  });

  // 2. 읽지 않은 알림 개수 조회
  const countRes = http.get(`${BASE_URL}/notifications/unread-count?_=${cacheBuster}`, params);
  unreadCountTime.add(countRes.timings.duration);
  apiResponseTime.add(countRes.timings.duration);

  const countSuccess = check(countRes, {
    '읽지 않은 개수 조회 성공': (r) => r.status === 200,
    '개수 조회 응답 시간 < 200ms': (r) => r.timings.duration < 200,
  });

  // 성공률 계산
  if (listSuccess && countSuccess) {
    apiSuccessRate.add(1);
  } else {
    apiSuccessRate.add(0);
    apiRequestsFailed.add(1);
    console.error(`API 요청 실패: userId=${user.userId}, list=${listRes.status}, count=${countRes.status}`);
  }

  sleep(0.1 + Math.random() * 0.2);  // 100-300ms 대기
}
