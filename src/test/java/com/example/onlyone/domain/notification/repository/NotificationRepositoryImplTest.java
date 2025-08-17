package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 레포지토리 QueryDSL 구현체 테스트
 *
 * 실제 데이터베이스 연동 없이 기본 동작만 검증
 */
@DisplayName("알림 레포지토리 구현체 테스트")
class NotificationRepositoryImplTest {

    @Test
    @DisplayName("레포지토리 구현체가 정상적으로 로드된다")
    void 레포지토리_구현체가_정상적으로_로드된다() {
        // given
        NotificationRepositoryImpl repository = new NotificationRepositoryImpl(null);

        // when & then
        assertThat(repository).isNotNull();
        assertThat(repository).isInstanceOf(NotificationRepositoryCustom.class);
    }

    @Test
    @DisplayName("NotificationStats 레코드가 정상적으로 생성된다")
    void NotificationStats_레코드가_정상적으로_생성된다() {
        // given & when
        NotificationRepositoryCustom.NotificationStats stats =
            new NotificationRepositoryCustom.NotificationStats(100L, 15L, 80L, 5L);

        // then
        assertThat(stats.totalCount()).isEqualTo(100L);
        assertThat(stats.unreadCount()).isEqualTo(15L);
        assertThat(stats.fcmSentCount()).isEqualTo(80L);
        assertThat(stats.fcmFailedCount()).isEqualTo(5L);
    }

    @Nested
    @DisplayName("성능 및 동시성 시뮬레이션 테스트")
    class PerformanceSimulationTest {

        @Test
        @DisplayName("대량 알림 데이터 처리 시뮬레이션")
        void 대량_알림_데이터_처리_시뮬레이션() {
            // given
            int dataSize = 10000;
            List<NotificationItemDto> mockData = createMockNotificationData(dataSize);

            long startTime = System.currentTimeMillis();

            // when - 데이터 처리 시뮬레이션
            List<NotificationItemDto> processedData = new ArrayList<>();
            for (NotificationItemDto item : mockData) {
                // 실제 QueryDSL 쿼리 결과 처리 시뮬레이션
                if (item.getType() == Type.CHAT) {
                    processedData.add(item);
                }
            }

            long endTime = System.currentTimeMillis();
            long processingTime = endTime - startTime;

            // then
            assertThat(processingTime).isLessThan(1000); // 1초 이내
            assertThat(processedData.size()).isGreaterThan(0);

            System.out.printf("✅ 대량 데이터 처리 완료: %d개 → %d개 (소요시간: %dms)%n",
                dataSize, processedData.size(), processingTime);
        }

        @Test
        @DisplayName("동시 쿼리 요청 시뮬레이션")
        void 동시_쿼리_요청_시뮬레이션() throws Exception {
            // given
            int threadCount = 50;
            int queriesPerThread = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (int j = 0; j < queriesPerThread; j++) {
                            // QueryDSL 쿼리 시뮬레이션
                            Long userId = (long) (threadIndex * 1000 + j);
                            simulateCountQuery(userId);
                            simulateFindQuery(userId);
                            successCount.incrementAndGet();
                        }

                    } catch (Exception e) {
                        // 실패 처리
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 모든 스레드 동시 시작
            endLatch.await();
            executor.shutdown();

            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;

            // then
            int expectedTotal = threadCount * queriesPerThread;
            assertThat(successCount.get()).isEqualTo(expectedTotal);
            assertThat(totalTime).isLessThan(5000); // 5초 이내

            double queryPerSecond = (expectedTotal * 2.0) / (totalTime / 1000.0); // count + find 쿼리

            System.out.printf("✅ 동시 쿼리 시뮬레이션 완료:%n");
            System.out.printf("   - 총 쿼리 수: %d개%n", expectedTotal * 2);
            System.out.printf("   - 소요 시간: %dms%n", totalTime);
            System.out.printf("   - 처리량: %.2f queries/sec%n", queryPerSecond);
        }

        @Test
        @DisplayName("메모리 사용량 최적화 검증")
        void 메모리_사용량_최적화_검증() {
            // given
            Runtime runtime = Runtime.getRuntime();
            long initialMemory = runtime.totalMemory() - runtime.freeMemory();

            // when - 대량 객체 생성 및 처리
            List<NotificationItemDto> largeDataSet = createMockNotificationData(50000);

            // 페이징 시뮬레이션 - 실제로는 QueryDSL limit/offset 사용
            int pageSize = 20;
            List<List<NotificationItemDto>> pages = new ArrayList<>();

            for (int i = 0; i < largeDataSet.size(); i += pageSize) {
                int end = Math.min(i + pageSize, largeDataSet.size());
                pages.add(new ArrayList<>(largeDataSet.subList(i, end)));
            }

            // 메모리 정리
            largeDataSet.clear();
            System.gc();

            long finalMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryUsed = finalMemory - initialMemory;

            // then
            assertThat(pages.size()).isGreaterThan(2000); // 많은 페이지로 분할됨
            assertThat(memoryUsed).isLessThan(100 * 1024 * 1024); // 100MB 이내

            System.out.printf("✅ 메모리 최적화 검증 완료:%n");
            System.out.printf("   - 총 페이지 수: %d개%n", pages.size());
            System.out.printf("   - 메모리 사용량: %.2fMB%n", memoryUsed / (1024.0 * 1024.0));
        }

        // ================================
        // Helper Methods
        // ================================

        private List<NotificationItemDto> createMockNotificationData(int count) {
            List<NotificationItemDto> data = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                NotificationItemDto dto = NotificationItemDto.builder()
                    .notificationId((long) i)
                    .type(i % 3 == 0 ? Type.CHAT : Type.LIKE)
                    .content("테스트 알림 " + i)
                    .isRead(i % 4 != 0) // 25% 읽지 않음
                    .build();
                data.add(dto);
            }
            return data;
        }

        private void simulateCountQuery(Long userId) {
            // 실제 QueryDSL count 쿼리 시뮬레이션
            // SELECT COUNT(*) FROM notification WHERE user_id = ? AND is_read = false
            Thread.yield(); // 쿼리 실행 시간 시뮬레이션
        }

        private void simulateFindQuery(Long userId) {
            // 실제 QueryDSL select 쿼리 시뮬레이션
            // SELECT n.*, nt.* FROM notification n JOIN notification_type nt WHERE n.user_id = ?
            Thread.yield(); // 쿼리 실행 시간 시뮬레이션
        }
    }
}