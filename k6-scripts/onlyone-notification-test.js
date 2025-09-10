import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const notificationRequests = new Counter('notification_requests');
const sseConnections = new Counter('sse_connections');
const apiErrors = new Counter('api_errors');

// 테스트 설정
export const options = {
  scenarios: {
    // SSE 연결 테스트
    sse_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 20 },   // 1분간 20 연결
        { duration: '2m', target: 50 },   // 2분간 50 연결  
        { duration: '2m', target: 100 },  // 2분간 100 연결
        { duration: '2m', target: 0 },    // 2분간 감소
      ],
      exec: 'testSSE',
    },
    
    // 알림 API 테스트
    notification_api: {
      executor: 'constant-arrival-rate',
      rate: 30,  // 초당 30개 요청
      timeUnit: '1s',
      duration: '7m',
      preAllocatedVUs: 20,
      maxVUs: 60,
      exec: 'testNotificationAPI',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.2'], // 실패율 20% 미만 (인증 오류 고려)
  },
};

const BASE_URL = 'http://host.docker.internal:8080';

// JWT 토큰 (로커스트와 동일)
const JWT_TOKEN = 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAwMDEiLCJrYWthb0lkIjoxMDAwMDEsIm5pY2tuYW1lIjoi6rmA7KeA66-8IiwidHlwZSI6ImFjY2VzcyIsImlhdCI6MTUxNjIzOTAyMiwiZXhwIjoxOTAwMDAwMDAwfQ.oGdxAoR1vex8PPcxDgGHIbF1Y7Q7DswrANLoJubaezc';

// 실제 알림 ID 풀
const NOTIFICATION_IDS = [5092476, 5092477, 5092478, 5095614, 5095615, 5098752, 5098753, 5101890, 5101891, 5101892];

// SSE 연결 테스트
export function testSSE() {
  const params = {
    headers: {
      'Accept': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Authorization': JWT_TOKEN,
    },
    timeout: '10s',
  };

  // 30% 확률로 Last-Event-ID 추가
  if (Math.random() < 0.3) {
    const timestamp = Date.now();
    params.headers['Last-Event-ID'] = `evt_${timestamp}`;
  }

  const startTime = Date.now();
  const response = http.get(`${BASE_URL}/sse/subscribe`, params);
  const duration = Date.now() - startTime;
  
  const success = check(response, {
    'SSE 연결 성공 또는 인증 오류': (r) => r.status === 200 || r.status === 401,
    'SSE 연결 시간 < 3초': () => duration < 3000,
  });
  
  if (response.status === 200) {
    sseConnections.add(1);
    console.log(`✅ SSE 연결 성공: ${duration}ms`);
  } else if (response.status === 401) {
    console.log(`🔐 SSE 인증 필요: ${duration}ms`);
  } else {
    apiErrors.add(1);
    console.log(`❌ SSE 연결 실패: Status ${response.status}, ${duration}ms`);
  }
  
  // 37초 문제 감지
  if (duration > 37000) {
    console.error(`🚨 37초 문제 발생! Duration: ${duration}ms`);
  }

  sleep(Math.random() * 3 + 2); // 2-5초 대기
}

// 알림 API 테스트
export function testNotificationAPI() {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': JWT_TOKEN,
    },
  };

  // 가중치 기반 API 선택 (로커스트 task 가중치 반영)
  const rand = Math.random();
  
  if (rand < 0.35) {
    // 읽지 않은 알림 개수 조회 (35%)
    testUnreadCount(params);
  } else if (rand < 0.55) {
    // 알림 목록 조회 (20%) 
    testNotificationList(params);
  } else if (rand < 0.71) {
    // 알림 시스템 상태 확인 (16%)
    testNotificationStatus(params);
  } else if (rand < 0.82) {
    // 개별 알림 읽음 처리 (11%)
    testMarkAsRead(params);
  } else if (rand < 0.91) {
    // 배치 알림 수신 확인 (9%)
    testAcknowledgeNotifications(params);
  } else if (rand < 0.96) {
    // 모든 알림 읽음 처리 (5%)
    testMarkAllAsRead(params);
  } else {
    // 누락 알림 복구 (4%)
    testRecoverNotifications(params);
  }

  sleep(Math.random() * 2 + 1); // 1-3초 대기
}

function testUnreadCount(params) {
  const response = http.get(`${BASE_URL}/notifications/unread-count`, params);
  notificationRequests.add(1);
  
  check(response, {
    '읽지 않은 알림 개수 조회 성공': (r) => r.status === 200 || r.status === 401,
  });
}

function testNotificationList(params) {
  let url = `${BASE_URL}/notifications`;
  
  // 30% 확률로 limit 파라미터 추가
  if (Math.random() < 0.3) {
    const limits = [10, 20, 50];
    const limit = limits[Math.floor(Math.random() * limits.length)];
    url += `?limit=${limit}`;
  }
  
  const response = http.get(url, params);
  notificationRequests.add(1);
  
  check(response, {
    '알림 목록 조회 성공': (r) => r.status === 200 || r.status === 401,
  });
}

function testNotificationStatus(params) {
  const response = http.get(`${BASE_URL}/notifications/status`, params);
  notificationRequests.add(1);
  
  check(response, {
    '알림 상태 조회 성공': (r) => r.status === 200 || r.status === 401,
  });
}

function testMarkAsRead(params) {
  const notificationId = NOTIFICATION_IDS[Math.floor(Math.random() * NOTIFICATION_IDS.length)];
  const response = http.put(`${BASE_URL}/notifications/${notificationId}/read`, null, params);
  notificationRequests.add(1);
  
  check(response, {
    '개별 알림 읽음 처리 성공': (r) => r.status === 200 || r.status === 401 || r.status === 404,
  });
}

function testAcknowledgeNotifications(params) {
  const count = Math.floor(Math.random() * 3) + 1;
  const notificationIds = [];
  for (let i = 0; i < count; i++) {
    notificationIds.push(NOTIFICATION_IDS[Math.floor(Math.random() * NOTIFICATION_IDS.length)]);
  }
  
  const response = http.post(`${BASE_URL}/notifications/acknowledge`, JSON.stringify(notificationIds), params);
  notificationRequests.add(1);
  
  check(response, {
    '배치 알림 수신 확인 성공': (r) => r.status === 200 || r.status === 401,
  });
}

function testMarkAllAsRead(params) {
  const response = http.put(`${BASE_URL}/notifications/read-all`, null, params);
  notificationRequests.add(1);
  
  check(response, {
    '모든 알림 읽음 처리 성공': (r) => r.status === 200 || r.status === 401,
  });
}

function testRecoverNotifications(params) {
  const timestamp = Date.now() - Math.floor(Math.random() * 60000); // 최근 1분 내
  const lastEventId = `evt_${timestamp}`;
  const response = http.post(`${BASE_URL}/notifications/recover?lastEventId=${lastEventId}`, null, params);
  notificationRequests.add(1);
  
  check(response, {
    '누락 알림 복구 성공': (r) => r.status === 200 || r.status === 401,
  });
}

// 기본 실행 함수
export default function () {
  testNotificationAPI();
}

// 테스트 결과 요약
export function handleSummary(data) {
  return {
    'stdout': `
=== OnlyOne 알림 시스템 테스트 결과 ===
총 HTTP 요청: ${data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0}
실패율: ${data.metrics.http_req_failed ? (data.metrics.http_req_failed.values.rate * 100).toFixed(2) : 0}%
평균 응답시간: ${data.metrics.http_req_duration ? data.metrics.http_req_duration.values.avg.toFixed(2) : 0}ms
p95 응답시간: ${data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'].toFixed(2) : 0}ms
p99 응답시간: ${data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(99)'].toFixed(2) : 0}ms

📊 커스텀 메트릭:
- 알림 API 요청 수: ${data.metrics.notification_requests ? data.metrics.notification_requests.values.count : 0}
- SSE 연결 수: ${data.metrics.sse_connections ? data.metrics.sse_connections.values.count : 0}
- API 오류 수: ${data.metrics.api_errors ? data.metrics.api_errors.values.count : 0}

🎯 테스트 목표 달성률:
- p95 응답시간 < 500ms: ${data.metrics.http_req_duration && data.metrics.http_req_duration.values['p(95)'] < 500 ? '✅' : '❌'}
- p99 응답시간 < 1000ms: ${data.metrics.http_req_duration && data.metrics.http_req_duration.values['p(99)'] < 1000 ? '✅' : '❌'}
- 실패율 < 20%: ${data.metrics.http_req_failed && data.metrics.http_req_failed.values.rate < 0.2 ? '✅' : '❌'}
    `,
  };
}