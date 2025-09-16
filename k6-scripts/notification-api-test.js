import { check, sleep } from 'k6';
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const notificationCreated = new Counter('notification_created');
const notificationRead = new Counter('notification_read');
const notificationDeleted = new Counter('notification_deleted');
const apiErrorRate = new Rate('api_error_rate');
const notificationResponseTime = new Trend('notification_response_time');

// 테스트 설정
export const options = {
  scenarios: {
    notification_load_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 20 },   // 1분간 20명까지 증가
        { duration: '3m', target: 50 },   // 3분간 50명 유지
        { duration: '2m', target: 100 },  // 2분간 100명까지 증가
        { duration: '5m', target: 100 },  // 5분간 100명 유지
        { duration: '2m', target: 0 },    // 2분간 감소
      ],
    },
    notification_spike_test: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '2m', target: 50 },   // 2분간 50 RPS까지 증가
        { duration: '1m', target: 200 },  // 1분간 200 RPS로 급증
        { duration: '2m', target: 50 },   // 2분간 50 RPS로 감소
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.1'],
    notification_response_time: ['p(95)<1500'],
    api_error_rate: ['rate<0.05'],
  },
};

const tokens = new SharedArray('auth_tokens', function () {
  return [
    'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMDAwMDEiLCJrYWthb0lkIjoxMDAwMDEsIm5pY2tuYW1lIjoi7Jyg7JiI7J2AIiwidHlwZSI6ImFjY2VzcyIsImlhdCI6MTc1NzQ4MDkwNywiZXhwIjoxOTAwMDAwMDAwfQ.p9B7tn-sQDr-1Wo9KF8Zkfc7OT490JN4vefaWuZqR5Y',
    'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMDAwMDIiLCJrYWthb0lkIjoxMDAwMDIsIm5pY2tuYW1lIjoi7J207ISx66-8IiwidHlwZSI6ImFjY2VzcyIsImlhdCI6MTc1NzQ4MDkwNywiZXhwIjoxOTAwMDAwMDAwfQ.wry-mcQ_g4Mz_XOoJh8deAfh16U19ZoE_tB6PG5XqFc',
    // 10-20개 정도 토큰이 있으면 충분
  ];
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const token = tokens[Math.floor(Math.random() * tokens.length)];
  const headers = {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  // 80% 확률로 알림 조회, 15% 확률로 읽음 처리, 5% 확률로 삭제
  const scenario = Math.random();
  
  if (scenario < 0.8) {
    testGetNotifications(headers);
  } else if (scenario < 0.95) {
    testMarkAsRead(headers);
  } else {
    testDeleteNotification(headers);
  }
  
  // 10% 확률로 읽지 않은 알림 개수 조회
  if (Math.random() < 0.1) {
    testGetUnreadCount(headers);
  }
  
  // 5% 확률로 모든 알림 읽음 처리
  if (Math.random() < 0.05) {
    testMarkAllAsRead(headers);
  }
  
  sleep(Math.random() * 3 + 1); // 1-4초 랜덤 대기
}

function testGetNotifications(headers) {
  const cursor = Math.random() < 0.3 ? `&cursor=${Math.floor(Math.random() * 1000)}` : '';
  const size = Math.floor(Math.random() * 30) + 1; // 1-30
  const url = `${BASE_URL}/api/notifications?size=${size}${cursor}`;
  
  const startTime = Date.now();
  const response = http.get(url, { headers });
  const responseTime = Date.now() - startTime;
  
  notificationResponseTime.add(responseTime);
  
  const success = check(response, {
    'notifications list status 200': (r) => r.status === 200,
    'notifications list has data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success === true && body.data && Array.isArray(body.data.notifications);
      } catch (e) {
        return false;
      }
    },
    'notifications response time < 2s': () => responseTime < 2000,
  });
  
  if (!success) {
    apiErrorRate.add(1);
    console.error(`[VU ${__VU}] 알림 목록 조회 실패: ${response.status} - ${response.body}`);
  }
  
  console.log(`[VU ${__VU}] 알림 목록 조회: ${response.status} (${responseTime}ms)`);
}

function testGetUnreadCount(headers) {
  const url = `${BASE_URL}/api/notifications/unread-count`;
  
  const startTime = Date.now();
  const response = http.get(url, { headers });
  const responseTime = Date.now() - startTime;
  
  const success = check(response, {
    'unread count status 200': (r) => r.status === 200,
    'unread count has data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success === true && typeof body.data === 'number';
      } catch (e) {
        return false;
      }
    },
    'unread count response time < 1s': () => responseTime < 1000,
  });
  
  if (!success) {
    apiErrorRate.add(1);
  }
  
  console.log(`[VU ${__VU}] 읽지 않은 알림 개수: ${response.status} (${responseTime}ms)`);
}

function testMarkAsRead(headers) {
  // 실제 환경에서는 먼저 알림 목록을 조회하여 실제 ID 사용
  const notificationId = Math.floor(Math.random() * 1000) + 1;
  const url = `${BASE_URL}/api/notifications/${notificationId}/read`;
  
  const startTime = Date.now();
  const response = http.put(url, null, { headers });
  const responseTime = Date.now() - startTime;
  
  const success = check(response, {
    'mark as read status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    'mark as read response time < 1s': () => responseTime < 1000,
  });
  
  if (response.status === 200) {
    notificationRead.add(1);
  }
  
  if (!success) {
    apiErrorRate.add(1);
  }
  
  console.log(`[VU ${__VU}] 알림 읽음 처리: ${response.status} (${responseTime}ms)`);
}

function testMarkAllAsRead(headers) {
  const url = `${BASE_URL}/api/notifications/read-all`;
  
  const startTime = Date.now();
  const response = http.put(url, null, { headers });
  const responseTime = Date.now() - startTime;
  
  const success = check(response, {
    'mark all as read status 200': (r) => r.status === 200,
    'mark all as read response time < 3s': () => responseTime < 3000,
  });
  
  if (!success) {
    apiErrorRate.add(1);
  }
  
  console.log(`[VU ${__VU}] 모든 알림 읽음 처리: ${response.status} (${responseTime}ms)`);
}

function testDeleteNotification(headers) {
  // 실제 환경에서는 먼저 알림 목록을 조회하여 실제 ID 사용
  const notificationId = Math.floor(Math.random() * 1000) + 1;
  const url = `${BASE_URL}/api/notifications/${notificationId}`;
  
  const startTime = Date.now();
  const response = http.del(url, null, { headers });
  const responseTime = Date.now() - startTime;
  
  const success = check(response, {
    'delete notification status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    'delete notification response time < 1s': () => responseTime < 1000,
  });
  
  if (response.status === 200) {
    notificationDeleted.add(1);
  }
  
  if (!success) {
    apiErrorRate.add(1);
  }
  
  console.log(`[VU ${__VU}] 알림 삭제: ${response.status} (${responseTime}ms)`);
}

// 테스트 시작 전 setup
export function setup() {
  console.log('=== 알림 API 부하 테스트 시작 ===');
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`Test Tokens: ${tokens.length}개`);
  
  // Health check
  const healthResponse = http.get(`${BASE_URL}/actuator/health`);
  if (healthResponse.status !== 200) {
    throw new Error(`Server health check failed: ${healthResponse.status}`);
  }
  
  return { startTime: Date.now() };
}

// 테스트 종료 후 teardown
export function teardown(data) {
  const duration = (Date.now() - data.startTime) / 1000;
  console.log(`=== 알림 API 부하 테스트 완료 (${duration}초) ===`);
}

// 결과 요약
export function handleSummary(data) {
  const summary = {
    testType: 'Notification API Load Test',
    duration: data.state.testRunDurationMs / 1000,
    metrics: {
      http_reqs: data.metrics.http_reqs?.values || {},
      http_req_duration: data.metrics.http_req_duration?.values || {},
      http_req_failed: data.metrics.http_req_failed?.values || {},
      notification_created: data.metrics.notification_created?.values || {},
      notification_read: data.metrics.notification_read?.values || {},
      notification_deleted: data.metrics.notification_deleted?.values || {},
      api_error_rate: data.metrics.api_error_rate?.values || {},
      notification_response_time: data.metrics.notification_response_time?.values || {},
    },
    thresholds: data.thresholds,
  };
  
  return {
    'notification-api-test-results.json': JSON.stringify(summary, null, 2),
    'notification-api-test-summary.txt': createTextSummary(summary),
  };
}

function createTextSummary(data) {
  let summary = `알림 API 부하 테스트 결과\n`;
  summary += `==========================\n\n`;
  
  summary += `테스트 지속 시간: ${data.duration}초\n\n`;
  
  if (data.metrics.http_reqs.count) {
    summary += `HTTP 요청:\n`;
    summary += `  총 요청: ${data.metrics.http_reqs.count}개\n`;
    summary += `  초당 요청: ${data.metrics.http_reqs.rate?.toFixed(2) || 'N/A'}/s\n\n`;
  }
  
  if (data.metrics.http_req_duration.avg) {
    summary += `응답 시간:\n`;
    summary += `  평균: ${data.metrics.http_req_duration.avg.toFixed(2)}ms\n`;
    summary += `  P95: ${data.metrics.http_req_duration['p(95)']?.toFixed(2) || 'N/A'}ms\n`;
    summary += `  최대: ${data.metrics.http_req_duration.max?.toFixed(2) || 'N/A'}ms\n\n`;
  }
  
  if (data.metrics.http_req_failed.rate !== undefined) {
    summary += `실패율: ${(data.metrics.http_req_failed.rate * 100).toFixed(2)}%\n\n`;
  }
  
  summary += `알림 API 작업:\n`;
  summary += `  읽음 처리: ${data.metrics.notification_read.count || 0}개\n`;
  summary += `  삭제: ${data.metrics.notification_deleted.count || 0}개\n\n`;
  
  if (data.thresholds) {
    summary += `임계값 통과:\n`;
    Object.entries(data.thresholds).forEach(([metric, result]) => {
      summary += `  ${metric}: ${result.ok ? '✓ 통과' : '✗ 실패'}\n`;
    });
  }
  
  return summary;
}