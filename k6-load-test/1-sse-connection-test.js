import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import sse from 'k6/x/sse';

// 커스텀 메트릭
const sseConnections = new Counter('sse_connections_total');
const sseErrors = new Counter('sse_errors_total');
const sseEvents = new Counter('sse_events_received');
const sseConnectionRate = new Rate('sse_success_rate');

// SSE 연결만 테스트
export const options = {
  scenarios: {
    sse_connections: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 200 },   // 30초간 200명까지
        { duration: '1m', target: 500 },    // 1분간 500명까지
        { duration: '2m', target: 1000 },   // 2분간 1000명까지
        { duration: '3m', target: 1000 },   // 3분간 1000명 유지
        { duration: '1m', target: 0 },      // 1분간 종료
      ],
      exec: 'sseTest',
    },
  },

  thresholds: {
    'sse_success_rate': ['rate>0.90'],  // 90% 연결 성공률
    'sse_connections_total': ['count>800'],  // 최소 800개 연결
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
        console.log(`SSE 연결 성공: userId=${user.userId}`);
        sseConnectionRate.add(1);
      });

      client.on('event', (event) => {
        sseEvents.add(1);
        check(event, {
          'SSE event received': (e) => e.data !== null,
        });
      });

      client.on('error', (error) => {
        console.error(`SSE 에러: userId=${user.userId}, error=${error}`);
        sseErrors.add(1);
        sseConnectionRate.add(0);
      });
    });

    // SSE 연결 유지 (20-60초 랜덤)
    sleep(20 + Math.random() * 40);

  } catch (error) {
    console.error(`SSE 연결 실패: userId=${user.userId}, error=${error}`);
    sseErrors.add(1);
    sseConnectionRate.add(0);
  }
}
