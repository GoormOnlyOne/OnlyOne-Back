import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import sse from 'k6/x/sse'; // xk6-sse 확장 필요

// 커스텀 메트릭
const sseConnections = new Counter('sse_connections_total');
const sseErrors = new Counter('sse_errors_total');
const sseEvents = new Counter('sse_events_received');
const notificationsCreated = new Counter('notifications_created');
const apiResponseTime = new Trend('api_response_time');
const sseConnectionRate = new Rate('sse_success_rate');
const notificationCreateRate = new Rate('notification_create_rate');

// 테스트 설정
export const options = {
  scenarios: {
    // SSE 연결 부하 테스트 (2000명 연결 유지)
    sse_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 200 },
        { duration: '1m', target: 1000 },
        { duration: '2m', target: 2000 },  // 2000명 동시 연결
        { duration: '5m', target: 2000 },
        { duration: '1m', target: 0 },
      ],
      exec: 'sseTest',
    },

    // 알림 집중 생성 테스트 (100명에게만 집중 공격)
    // 500개/초 ÷ 100명 = 유저당 5개/초 SSE 전송 부하
    notification_create_load: {
      executor: 'constant-arrival-rate',
      rate: 1000,  // 초당 1000개 알림 생성 (증가)
      timeUnit: '1s',
      duration: '8m',
      preAllocatedVUs: 100,
      maxVUs: 500,
      exec: 'notificationCreateTest',
      startTime: '1m',  // SSE 연결 후 시작
    },

    // 알림 조회 API 부하 테스트
    api_load: {
      executor: 'constant-arrival-rate',
      rate: 200,  // RPS
      timeUnit: '1s',
      duration: '8m',
      preAllocatedVUs: 100,
      maxVUs: 300,
      exec: 'apiTest',
      startTime: '30s',
    },
  },

  thresholds: {
    'http_req_duration': ['p(95)<2000'],  // 임계값 완화
    'http_req_failed': ['rate<0.10'],     // 10% 허용
    'sse_success_rate': ['rate>0.85'],    // 85% 성공률
    'notification_create_rate': ['rate>0.90'],  // 알림 생성 성공률 90%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

// tokens.json 파일에서 토큰 로드 (init 단계)
const tokensFile = open('./tokens.json');
const userTokens = JSON.parse(tokensFile);

// 테스트 사용자 준비 (init 단계)
let testUsers = [];
for (const userId in userTokens) {
  testUsers.push({
    userId: parseInt(userId),
    token: userTokens[userId],
  });
}

console.log(`✅ ${testUsers.length}명의 사용자 토큰 로드 완료`);

export function setup() {
  // 이미 init 단계에서 로드된 testUsers를 반환
  return { testUsers };
}

// 인증 토큰 반환 (미리 생성된 토큰 사용)
function getAuthToken(userId) {
  const token = userTokens[userId];
  if (!token) {
    throw new Error(`토큰 없음 for user ${userId}`);
  }
  return token;
}

// SSE 연결 테스트
export function sseTest(data) {
  const user = data.testUsers[Math.floor(Math.random() * data.testUsers.length)];
  const token = getAuthToken(user.userId);

  sseConnections.add(1);

  try {
    const client = sse.open(`${BASE_URL}/sse/subscribe`, {
      headers: {
        'Authorization': `Bearer ${token}`,
      },
    }, (client) => {
      client.on('open', () => {
        sseConnectionRate.add(1);
      });

      client.on('event', (event) => {
        sseEvents.add(1);
        check(event, {
          'SSE event received': (e) => e.data !== null,
        });
      });

      client.on('error', (error) => {
        sseErrors.add(1);
        sseConnectionRate.add(0);
      });
    });

    // SSE 연결 유지 (10-60초 랜덤)
    sleep(10 + Math.random() * 50);

  } catch (error) {
    sseErrors.add(1);
    sseConnectionRate.add(0);
  }
}

// API 테스트
export function apiTest(data) {
  const user = data.testUsers[Math.floor(Math.random() * data.testUsers.length)];
  const token = getAuthToken(user.userId);

  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };

  // 알림 목록 조회
  const listRes = http.get(`${BASE_URL}/notifications?size=20`, params);
  check(listRes, {
    '알림 목록 조회 성공': (r) => r.status === 200,
  });
  apiResponseTime.add(listRes.timings.duration);

  // 읽지 않은 알림 개수
  http.get(`${BASE_URL}/notifications/unread-count`, params);

  sleep(0.1);
}

// 알림 집중 생성 테스트 (특정 100명에게만 집중 공격)
// 이렇게 하면 SSE 전송 부하가 확실히 발생함
export function notificationCreateTest(data) {
  // 전체 1000명 중 1-100번 100명에게만 알림 생성
  const targetUserIds = Array.from({length: 100}, (_, i) => 1 + i);
  const targetUserId = targetUserIds[Math.floor(Math.random() * targetUserIds.length)];

  const token = getAuthToken(targetUserId);

  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };

  const types = ['CHAT', 'LIKE', 'COMMENT', 'REFEED', 'SETTLEMENT'];
  const type = types[Math.floor(Math.random() * types.length)];

  // 알림 생성 (1000개/초 ÷ 100명 = 유저당 10개/초)
  const createRes = http.post(`${BASE_URL}/notifications`, JSON.stringify({
    type: type,
    args: [`테스트유저${Math.floor(Math.random() * 100)}`],
  }), params);

  const success = createRes.status === 201;
  notificationCreateRate.add(success);

  if (success) {
    notificationsCreated.add(1);
  }

  check(createRes, {
    '알림 생성 성공': (r) => r.status === 201,
  });

  sleep(0.01);
}
