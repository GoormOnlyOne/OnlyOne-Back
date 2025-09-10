# Jaeger 트레이싱 사용 가이드

## 1. 시작하기

### Jaeger 실행
```bash
./jaeger-start.sh
```

### Spring Boot 실행
```bash
./gradlew bootRun
```

## 2. 메서드별 시간 측정 방법

### @Observed 어노테이션 사용
```java
import io.micrometer.observation.annotation.Observed;

@Service
public class NotificationService {
    
    @Observed(name = "notification.create", 
              contextualName = "creating-notification",
              lowCardinalityKeyValues = {"type", "notification"})
    public Notification createNotification(User user, Type type) {
        // 이 메서드의 실행시간이 Jaeger에 자동 기록됨
        return notification;
    }
}
```

### Repository 메서드 추적
```java
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    
    @Observed(name = "notification.query.unread")
    List<Notification> findByUserIdAndReadFalse(Long userId);
}
```

### Controller 추적
```java
@RestController
public class NotificationController {
    
    @GetMapping("/notifications")
    @Observed(name = "api.notifications.list")
    public List<NotificationDto> getNotifications() {
        // API 응답시간 측정
    }
}
```

## 3. 트레이스 확인

1. Jaeger UI 접속: http://localhost:16686
2. Service 선택: `onlyone-backend`
3. Operation 선택 (예: `notification.create`)
4. Find Traces 클릭

## 4. 성능 병목 찾기

### 트레이스 분석
- **Duration**: 각 메서드 실행 시간
- **Span Count**: 호출된 메서드 수
- **Critical Path**: 가장 오래 걸린 경로

### 문제 있는 메서드 예시
```
notification.create (5000ms) ← 너무 느림!
  ├─ database.query (4500ms) ← 병목 발견!
  ├─ cache.lookup (50ms)
  └─ event.publish (450ms)
```

## 5. 37초 응답시간 원인 찾기

### 확인할 포인트
1. **DB 쿼리 시간**: `spring.datasource` spans
2. **트랜잭션 대기**: `transaction.commit` spans  
3. **SSE 연결**: `sse.connection` spans
4. **비동기 처리**: `@Async` method spans

### 샘플 쿼리
Jaeger UI에서:
- Service: `onlyone-backend`
- Min Duration: `5s` (5초 이상만)
- Limit: 20

## 6. 커스텀 Span 생성

```java
@Autowired
private Tracer tracer;

public void complexMethod() {
    Span span = tracer.nextSpan()
        .name("custom.operation")
        .tag("user.id", "123")
        .start();
    
    try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
        // 측정하고 싶은 코드
        doSomething();
    } finally {
        span.end();
    }
}
```

## 7. 주의사항

- **샘플링 비율**: 개발(1.0) vs 운영(0.1)
- **메모리 사용량**: 트레이스 데이터 저장 공간
- **성능 오버헤드**: 약 1-3% 성능 저하

## 8. 문제 해결

### Jaeger 연결 실패
```bash
docker-compose -f docker-compose-jaeger.yml logs jaeger
```

### 트레이스가 안 보일 때
1. `management.tracing.sampling.probability: 1.0` 확인
2. `@Observed` 어노테이션 확인
3. ObservedAspect Bean 등록 확인