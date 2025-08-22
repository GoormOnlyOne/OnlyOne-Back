package com.example.onlyone.performance;

/**
 * 알림 시스템 성능 테스트 목표치 및 설정
 */
public class PerformanceTestConfig {

    // ========================
    // 성능 목표치 정의
    // ========================
    
    /**
     * 알림 조회 성능 목표
     */
    public static class NotificationQuery {
        public static final int MAX_RESPONSE_TIME_MS = 500;     // 500ms 이내
        public static final int TARGET_TPS = 1000;              // 초당 1000건 처리
        public static final int MAX_CONCURRENT_USERS = 100;     // 동시 100명 지원
        public static final int PAGE_SIZE = 20;                 // 페이지당 20개
        public static final int MAX_PAGES_TO_TEST = 50;         // 최대 50페이지까지 테스트
    }

    /**
     * FCM 전송 성능 목표
     */
    public static class FcmPerformance {
        public static final int SINGLE_SEND_MAX_TIME_MS = 1000; // 단건 전송 1초 이내
        public static final int BATCH_SEND_MAX_TIME_MS = 5000;  // 배치 전송 5초 이내 (500건)
        public static final int MAX_BATCH_SIZE = 500;           // 배치 최대 크기
        public static final int TARGET_HOURLY_VOLUME = 100000;  // 시간당 10만건 처리 목표
        public static final int MAX_RETRY_ATTEMPTS = 3;         // 최대 재시도 횟수
    }

    /**
     * SSE 연결 성능 목표
     */
    public static class SsePerformance {
        public static final int CONNECTION_SETUP_MAX_TIME_MS = 200;    // 연결 설정 200ms 이내
        public static final int MESSAGE_DELIVERY_MAX_TIME_MS = 100;    // 메시지 전달 100ms 이내
        public static final int MAX_CONCURRENT_CONNECTIONS = 1000;     // 동시 연결 1000개
        public static final int BROADCAST_MAX_TIME_MS = 2000;          // 브로드캐스트 2초 이내
        public static final int CONNECTION_MEMORY_LIMIT_MB = 1;        // 연결당 메모리 1MB 이하
    }

    /**
     * 전체 시스템 성능 목표
     */
    public static class SystemPerformance {
        public static final int MEMORY_USAGE_MAX_MB = 512;             // 최대 메모리 사용량 512MB
        public static final double CPU_USAGE_MAX_PERCENT = 80.0;       // 최대 CPU 사용률 80%
        public static final int DB_CONNECTION_POOL_SIZE = 20;          // DB 커넥션 풀 크기
        public static final int THREAD_POOL_SIZE = 50;                 // 스레드 풀 크기
    }

    // ========================
    // 테스트 데이터 설정
    // ========================
    
    /**
     * 성능 테스트용 데이터 볼륨
     */
    public static class TestDataVolume {
        public static final int SMALL_DATASET_USERS = 100;       // 소규모: 100명
        public static final int MEDIUM_DATASET_USERS = 1000;     // 중규모: 1000명
        public static final int LARGE_DATASET_USERS = 10000;     // 대규모: 10000명
        
        public static final int NOTIFICATIONS_PER_USER = 50;     // 사용자당 알림 50개
        public static final int BULK_INSERT_BATCH_SIZE = 1000;   // 대량 삽입 배치 크기
    }

    /**
     * 동시성 테스트 설정
     */
    public static class ConcurrencyTest {
        public static final int THREAD_COUNT_LIGHT = 10;         // 가벼운 부하
        public static final int THREAD_COUNT_MEDIUM = 50;        // 중간 부하
        public static final int THREAD_COUNT_HEAVY = 100;        // 무거운 부하
        
        public static final int TEST_DURATION_SECONDS = 30;      // 테스트 지속 시간
        public static final int WARMUP_DURATION_SECONDS = 5;     // 워밍업 시간
    }

    // ========================
    // 측정 기준
    // ========================
    
    /**
     * 성능 측정 허용 오차율
     */
    public static class Tolerance {
        public static final double RESPONSE_TIME_TOLERANCE = 0.1;      // 응답시간 10% 허용
        public static final double THROUGHPUT_TOLERANCE = 0.05;        // 처리량 5% 허용
        public static final double MEMORY_USAGE_TOLERANCE = 0.15;      // 메모리 사용량 15% 허용
    }

    /**
     * 성공률 목표
     */
    public static class SuccessRate {
        public static final double MIN_SUCCESS_RATE = 0.99;            // 최소 99% 성공률
        public static final double TARGET_SUCCESS_RATE = 0.999;        // 목표 99.9% 성공률
        public static final double MAX_ERROR_RATE = 0.01;              // 최대 1% 오류율
    }
}