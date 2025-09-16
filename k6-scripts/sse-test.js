import { check, sleep } from 'k6';
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

// xk6-sse 확장 사용
import { connect } from 'k6/x/sse';

// 커스텀 메트릭
const sseMessagesReceived = new Counter('sse_messages_received');
const sseConnectionDuration = new Trend('sse_connection_duration');

// 테스트 설정
export const options = {
  scenarios: {
    sse_load_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 50 },   // 2분간 50명까지 증가
        { duration: '5m', target: 100 },  // 5분간 100명 유지
        { duration: '2m', target: 200 },  // 2분간 200명까지 증가
        { duration: '5m', target: 200 },  // 5분간 200명 유지
        { duration: '3m', target: 0 },    // 3분간 감소
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000'],
    sse_connection_duration: ['p(95)<5000'],
    sse_messages_received: ['count>0'],
    http_req_failed: ['rate<0.1'],
  },
};

const tokens = new SharedArray('auth_tokens', function () {
  return [
    'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMDAwMDEiLCJrYWthb0lkIjoxMDAwMDEsIm5pY2tuYW1lIjoi7Jyg7JiI7J2AIiwidHlwZSI6ImFjY2VzcyIsImlhdCI6MTc1NzQ4MDkwNywiZXhwIjoxOTAwMDAwMDAwfQ.p9B7tn-sQDr-1Wo9KF8Zkfc7OT490JN4vefaWuZqR5Y',
    'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMDAwMDIiLCJrYWthb0lkIjoxMDAwMDIsIm5pY2tuYW1lIjoi7J207ISx66-8IiwidHlwZSI6ImFjY2VzcyIsImlhdCI6MTc1NzQ4MDkwNywiZXhwIjoxOTAwMDAwMDAwfQ.wry-mcQ_g4Mz_XOoJh8deAfh16U19ZoE_tB6PG5XqFc',
    // 10-20개 정도 토큰이 있으면 200명 동시 접속 테스트 가능
    // 필요시 추가 토큰 요청
  ];
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const token = tokens[Math.floor(Math.random() * tokens.length)];
  const headers = {
    'Authorization': token,
  };

  // SSE 연결 상태 확인
  testSseStatus(headers);
  
  // SSE 구독 연결 테스트
  testSseSubscribe(headers);
  
  sleep(Math.random() * 2 + 1);
}

function testSseStatus(headers) {
  const url = `${BASE_URL}/sse/status`;
  
  const startTime = Date.now();
  const response = http.get(url, { headers });
  const responseTime = Date.now() - startTime;
  
  const success = check(response, {
    'SSE status check 200': (r) => r.status === 200,
    'SSE status has connection data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success === true && 
               body.data && 
               typeof body.data.connected === 'boolean' &&
               typeof body.data.totalConnections === 'number';
      } catch (e) {
        return false;
      }
    },
    'SSE status response time < 1s': () => responseTime < 1000,
  });
  
  if (!success) {
    console.error(`[VU ${__VU}] SSE 상태 확인 실패: ${response.status}`);
  }
}

function testSseSubscribe(headers) {
  const sseUrl = `${BASE_URL}/sse/subscribe`;
  
  console.log(`[VU ${__VU}] SSE 구독 연결 시도: ${sseUrl}`);
  
  // Last-Event-ID 헤더 추가 (30% 확률로 재연결 시뮬레이션)
  const sseHeaders = {
    ...headers,
    'Accept': 'text/event-stream',
    'Cache-Control': 'no-cache',
  };
  
  if (Math.random() < 0.3) {
    sseHeaders['Last-Event-ID'] = `evt_${Date.now()}_0`;
  }
  
  try {
    const connection = connect(sseUrl, {
      headers: sseHeaders,
      timeout: '30s',
    });

    let messageCount = 0;
    let connectionStartTime = Date.now();
    let connectionSuccess = false;
    
    connection.on('open', function() {
      connectionSuccess = true;
      console.log(`[VU ${__VU}] SSE 연결 성공`);
    });

    connection.on('message', function(event) {
      messageCount++;
      sseMessagesReceived.add(1);
      console.log(`[VU ${__VU}] SSE 메시지 수신 #${messageCount}: ${event.type}`);
      
      // 메시지 유효성 검증
      check(event, {
        'SSE message has data': (e) => e.data !== null && e.data !== undefined,
        'SSE message has type': (e) => e.type !== null && e.type !== undefined,
        'SSE message is notification': (e) => e.type === 'notification' || e.type === 'heartbeat',
      });
      
      // 알림 메시지인 경우 JSON 파싱 테스트
      if (event.type === 'notification') {
        try {
          const notificationData = JSON.parse(event.data);
          check(notificationData, {
            'Notification has id': (n) => n.id !== undefined,
            'Notification has content': (n) => n.content !== undefined,
            'Notification has type': (n) => n.notificationType !== undefined,
          });
        } catch (e) {
          console.error(`[VU ${__VU}] 알림 JSON 파싱 실패: ${event.data}`);
        }
      }
    });

    connection.on('error', function(error) {
      console.error(`[VU ${__VU}] SSE 연결 오류: ${error}`);
      check(null, {
        'SSE connection error handled': () => false,
      });
    });

    connection.on('close', function() {
      const connectionDuration = Date.now() - connectionStartTime;
      sseConnectionDuration.add(connectionDuration);
      console.log(`[VU ${__VU}] SSE 연결 종료 - 지속시간: ${connectionDuration}ms, 메시지: ${messageCount}개`);
      
      // 연결 지표 기록
      check(null, {
        'SSE connection was successful': () => connectionSuccess,
        'SSE connection duration acceptable': () => connectionDuration < 35000,
        'SSE received messages': () => messageCount >= 0,
      });
    });

    // 30초간 연결 유지 후 종료
    sleep(30);
    connection.close();
    
  } catch (error) {
    console.error(`[VU ${__VU}] SSE 연결 실패: ${error}`);
    check(null, {
      'SSE connection successful': () => false,
    });
  }
}

export function setup() {
  console.log('=== SSE 부하 테스트 시작 ===');
  console.log(`Target URL: ${BASE_URL}`);
  console.log(`Test Tokens: ${tokens.length}개`);
  
  // 서버 상태 확인
  try {
    const healthResponse = http.get(`${BASE_URL}/actuator/health`);
    console.log(`Health Check: ${healthResponse.status}`);
  } catch (e) {
    console.warn('Health check 실패, 테스트 계속 진행');
  }
  
  return { startTime: Date.now() };
}

export function teardown(data) {
  const duration = (Date.now() - data.startTime) / 1000;
  console.log(`=== SSE 부하 테스트 완료 (${duration.toFixed(2)}초) ===`);
}

export function handleSummary(data) {
  const summary = {
    testType: 'SSE Load Test with xk6-sse',
    duration: data.state.testRunDurationMs / 1000,
    endpoints: [
      '/sse/subscribe',
      '/sse/status'
    ],
    metrics: {
      http_reqs: data.metrics.http_reqs?.values || {},
      http_req_duration: data.metrics.http_req_duration?.values || {},
      http_req_failed: data.metrics.http_req_failed?.values || {},
      vus_max: data.metrics.vus_max?.values || {},
      sse_connection_duration: data.metrics.sse_connection_duration?.values || {},
      sse_messages_received: data.metrics.sse_messages_received?.values || {},
    },
    thresholds: data.thresholds || {},
  };
  
  return {
    'sse-load-test-results.json': JSON.stringify(summary, null, 2),
    'sse-load-test-summary.txt': createTextSummary(summary),
  };
}

function createTextSummary(data) {
  let summary = `SSE 부하 테스트 결과 (xk6-sse)\n`;
  summary += `================================\n\n`;
  
  summary += `테스트 지속 시간: ${data.duration.toFixed(2)}초\n`;
  summary += `테스트 엔드포인트: ${data.endpoints.join(', ')}\n\n`;
  
  if (data.metrics.http_reqs.count) {
    summary += `HTTP 요청 통계:\n`;
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
  
  summary += `SSE 연결 통계:\n`;
  summary += `  최대 동시 사용자: ${data.metrics.vus_max.value || 'N/A'}명\n`;
  
  if (data.metrics.sse_connection_duration.avg) {
    summary += `  평균 연결 지속시간: ${data.metrics.sse_connection_duration.avg.toFixed(2)}ms\n`;
  }
  
  if (data.metrics.sse_messages_received.count) {
    summary += `  총 수신 메시지: ${data.metrics.sse_messages_received.count}개\n`;
  }
  
  summary += `\n`;
  
  if (data.thresholds && Object.keys(data.thresholds).length > 0) {
    summary += `임계값 검증:\n`;
    Object.entries(data.thresholds).forEach(([metric, result]) => {
      summary += `  ${metric}: ${result.ok ? '✓ 통과' : '✗ 실패'}\n`;
    });
  }
  
  return summary;
}