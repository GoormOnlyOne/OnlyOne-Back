import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const errorRate = new Rate('errors');
const sseConnections = new Counter('sse_connections');
const apiResponseTime = new Trend('api_response_time');

// 테스트 설정 - 10,000 사용자 시뮬레이션
export const options = {
  stages: [
    { duration: '2m', target: 100 },    // 2분간 100명까지 증가
    { duration: '3m', target: 500 },    // 3분간 500명까지 증가
    { duration: '5m', target: 1000 },   // 5분간 1000명까지 증가
    { duration: '5m', target: 5000 },   // 5분간 5000명까지 증가
    { duration: '10m', target: 10000 }, // 10분간 10000명까지 증가
    { duration: '10m', target: 10000 }, // 10분간 10000명 유지
    { duration: '5m', target: 0 },      // 5분간 감소
  ],
  thresholds: {
    http_req_duration: ['p(95)<5000', 'p(99)<10000'], // 95%는 5초, 99%는 10초 이내
    http_req_failed: ['rate<0.1'],                      // 에러율 10% 미만
    errors: ['rate<0.1'],                                // 커스텀 에러율 10% 미만
  },
};

// 베이스 URL
const BASE_URL = 'http://localhost:8080';

// 테스트 데이터
const users = Array.from({ length: 10000 }, (_, i) => ({
  userId: i + 1,
  username: `user${i + 1}`,
  token: `test_token_${i + 1}`, // 실제 JWT 토큰으로 교체 필요
}));

// 설정 함수
export function setup() {
  console.log('🚀 K6 Load Test Starting...');
  console.log(`📊 Target: ${options.stages[4].target} concurrent users`);
  console.log(`🌐 Base URL: ${BASE_URL}`);
  
  // 헬스체크
  const healthCheck = http.get(`${BASE_URL}/actuator/health`);
  if (healthCheck.status !== 200) {
    throw new Error('Server is not healthy!');
  }
  
  return { startTime: new Date().toISOString() };
}

// 메인 테스트 시나리오
export default function (data) {
  const user = users[__VU % users.length]; // Virtual User별 다른 사용자
  const headers = {
    'Authorization': `Bearer ${user.token}`,
    'Content-Type': 'application/json',
  };

  // 시나리오 1: SSE 연결 (30% 사용자)
  if (__VU % 10 < 3) {
    testSSEConnection(user, headers);
  }
  
  // 시나리오 2: 알림 목록 조회 (40% 사용자)
  if (__VU % 10 >= 3 && __VU % 10 < 7) {
    testNotificationList(user, headers);
  }
  
  // 시나리오 3: 알림 생성 (20% 사용자)
  if (__VU % 10 >= 7 && __VU % 10 < 9) {
    testCreateNotification(user, headers);
  }
  
  // 시나리오 4: 혼합 시나리오 (10% 사용자)
  if (__VU % 10 >= 9) {
    testMixedScenario(user, headers);
  }
  
  sleep(Math.random() * 3 + 1); // 1-4초 랜덤 대기
}

// SSE 연결 테스트
function testSSEConnection(user, headers) {
  const params = {
    headers: {
      ...headers,
      'Accept': 'text/event-stream',
      'Last-Event-ID': Math.random() > 0.5 ? '31' : '', // 50% 재연결 시뮬레이션
    },
    timeout: '30s',
  };
  
  const response = http.get(`${BASE_URL}/sse/subscribe`, params);
  
  const success = check(response, {
    'SSE connection established': (r) => r.status === 200 || r.status === 204,
  });
  
  errorRate.add(!success);
  if (success) {
    sseConnections.add(1);
  }
  
  // SSE 연결 유지 시뮬레이션
  sleep(Math.random() * 10 + 5); // 5-15초 연결 유지
}

// 알림 목록 조회 테스트
function testNotificationList(user, headers) {
  const startTime = new Date();
  
  const response = http.get(`${BASE_URL}/api/v1/notifications?page=0&size=20`, {
    headers,
    timeout: '60s', // 37초 문제 감지용
  });
  
  const duration = new Date() - startTime;
  apiResponseTime.add(duration);
  
  const success = check(response, {
    'Get notifications success': (r) => r.status === 200,
    'Response time < 1s': (r) => duration < 1000,
    'Response time < 5s': (r) => duration < 5000,
    'Has notifications': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && Array.isArray(body.data.content);
      } catch {
        return false;
      }
    },
  });
  
  errorRate.add(!success);
  
  if (duration > 37000) {
    console.error(`🚨 37초 문제 발생! User: ${user.userId}, Duration: ${duration}ms`);
  }
}

// 알림 생성 테스트
function testCreateNotification(user, headers) {
  const payload = JSON.stringify({
    type: 'CHAT',
    userId: user.userId,
    message: `Test notification from K6 - ${new Date().toISOString()}`,
    priority: Math.random() > 0.8 ? 'HIGH' : 'NORMAL',
  });
  
  const response = http.post(`${BASE_URL}/api/v1/notifications`, payload, {
    headers,
    timeout: '10s',
  });
  
  const success = check(response, {
    'Create notification success': (r) => r.status === 200 || r.status === 201,
  });
  
  errorRate.add(!success);
}

// 혼합 시나리오 테스트
function testMixedScenario(user, headers) {
  // 1. 알림 목록 조회
  const listResponse = http.get(`${BASE_URL}/api/v1/notifications`, { headers });
  
  check(listResponse, {
    'Mixed: List success': (r) => r.status === 200,
  });
  
  // 2. 읽음 처리
  if (listResponse.status === 200) {
    try {
      const notifications = JSON.parse(listResponse.body).data.content;
      if (notifications && notifications.length > 0) {
        const notificationId = notifications[0].id;
        
        const ackResponse = http.patch(
          `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
          null,
          { headers }
        );
        
        check(ackResponse, {
          'Mixed: Acknowledge success': (r) => r.status === 200,
        });
      }
    } catch (e) {
      console.error('Mixed scenario parse error:', e);
    }
  }
  
  sleep(1);
}

// 테스트 종료 함수
export function teardown(data) {
  console.log('📊 K6 Load Test Completed!');
  console.log(`⏱️ Started: ${data.startTime}`);
  console.log(`⏱️ Ended: ${new Date().toISOString()}`);
}