import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// 커스텀 메트릭
const notificationsCreated = new Counter('notifications_created');
const notificationsFailed = new Counter('notifications_failed');
const notificationCreateRate = new Rate('notification_create_rate');
const notificationResponseTime = new Trend('notification_response_time');

// 알림 생성만 테스트
export const options = {
  scenarios: {
    notification_create: {
      executor: 'constant-arrival-rate',
      rate: 500,  // 초당 500개 알림 생성
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 100,
      maxVUs: 500,
      exec: 'notificationCreateTest',
    },
  },

  thresholds: {
    'http_req_duration': ['p(95)<1000', 'p(99)<3000'],  // 95% < 1초, 99% < 3초
    'notification_create_rate': ['rate>0.95'],  // 95% 성공률
    'notifications_created': ['count>2000'],  // 최소 2000개 생성
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

// 알림 생성 테스트
export function notificationCreateTest(data) {
  // 랜덤 사용자 선택
  const user = data.testUsers[Math.floor(Math.random() * data.testUsers.length)];
  const token = getAuthToken(user.userId);

  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };

  // 알림 타입과 인자 설정
  const notificationTypes = [
    { type: 'CHAT', args: [`테스트유저${Math.floor(Math.random() * 1000)}`] },
    { type: 'LIKE', args: [`테스트유저${Math.floor(Math.random() * 1000)}`] },
    { type: 'COMMENT', args: [`테스트유저${Math.floor(Math.random() * 1000)}`, '테스트 댓글'] },
    { type: 'REFEED', args: ['테스트 피드'] },
    { type: 'SETTLEMENT', args: ['완료'] },
  ];

  const notification = notificationTypes[Math.floor(Math.random() * notificationTypes.length)];

  // 알림 생성 요청
  const createRes = http.post(`${BASE_URL}/notifications`, JSON.stringify({
    type: notification.type,
    args: notification.args,
  }), params);

  notificationResponseTime.add(createRes.timings.duration);

  const success = createRes.status === 201;
  notificationCreateRate.add(success);

  if (success) {
    notificationsCreated.add(1);
  } else {
    notificationsFailed.add(1);
    console.error(`알림 생성 실패: userId=${user.userId}, status=${createRes.status}, body=${createRes.body}`);
  }

  check(createRes, {
    '알림 생성 성공': (r) => r.status === 201,
    '응답 시간 < 1초': (r) => r.timings.duration < 1000,
  });

  sleep(0.01);
}
