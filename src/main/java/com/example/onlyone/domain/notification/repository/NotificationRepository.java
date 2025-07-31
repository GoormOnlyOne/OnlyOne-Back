package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.NotificationListItem;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 알림 리포지토리
 *
 * Notification 엔티티에 대한 모든 데이터 액세스 작업을 담당합니다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 사용자의 읽지 않은 알림 목록 조회 (엔티티 전체)
     *
     * 읽지 않은 알림들의 상세 정보가 필요할 때 사용합니다.
     * 주로 읽음 처리나 FCM 전송 등의 비즈니스 로직에서 활용됩니다.
     *
     * @param userId 사용자 ID
     * @return 읽지 않은 알림 엔티티 목록
     */
    List<Notification> findByUser_UserIdAndIsReadFalse(Long userId);

    /**
     * 사용자의 읽지 않은 알림 개수 조회
     *
     * SSE를 통한 실시간 카운트 업데이트에 사용됩니다.
     *
     * @param userId 사용자 ID
     * @return 읽지 않은 알림 개수
     */
    long countByUser_UserIdAndIsReadFalse(Long userId);

    /**
     * 첫 페이지 조회 (NotificationListItem 프로젝션 사용)
     *
     * 알림 목록의 첫 페이지를 조회합니다.
     * 프로젝션을 사용하여 필요한 필드만 조회해 성능을 최적화했습니다.
     * notification_id DESC 정렬로 최신 알림이 먼저 표시됩니다.
     *
     * @param userId 사용자 ID
     * @param pageable 페이지 정보 (주로 size만 사용)
     * @return 알림 목록 (프로젝션)
     *
     * 사용 시나리오:
     * - 앱 실행 시 최신 알림 목록 로드
     * - 알림 화면 진입 시 초기 데이터 표시
     */
     @Query("""
        SELECT new com.example.onlyone.domain.notification.dto.NotificationListItem(
            n.notificationId, 
            n.content, 
            n.notificationType.type, 
            n.isRead, 
            n.createdAt
        )
        FROM Notification n
        WHERE n.user.userId = :userId
        ORDER BY n.notificationId DESC
        """)
    List<NotificationListItem> findTopByUser(
        @Param("userId") Long userId,
        Pageable pageable);

    /**
     * 커서 이후 페이지 조회 (NotificationListItem 프로젝션 사용)
     *
     * 커서 기반 페이징의 핵심 메서드입니다.
     * 커서(이전 페이지의 마지막 notification_id)보다 작은 ID를 가진 알림들을 조회합니다.
     *
     * @param userId 사용자 ID
     * @param cursor 이전 페이지의 마지막 notification_id
     * @param pageable 페이지 정보 (size)
     * @return 커서 이후의 알림 목록 (프로젝션)
     *
     * 사용 시나리오:
     * - 무한 스크롤 구현
     * - "더보기" 버튼 클릭 시 추가 데이터 로드
     */
     @Query("""
        SELECT new com.example.onlyone.domain.notification.dto.NotificationListItem(
            n.notificationId, 
            n.content, 
            n.notificationType.type, 
            n.isRead, 
            n.createdAt
        )
        FROM Notification n
        WHERE n.user.userId = :userId
          AND n.notificationId < :cursor
        ORDER BY n.notificationId DESC
        """)
    List<NotificationListItem> findAfterCursor(
        @Param("userId") Long userId,
        @Param("cursor") Long cursor,
        Pageable pageable);

    /**
     * 특정 타입의 알림만 조회 (프로젝션 사용) (예정)
     *
     * 사용자가 특정 타입의 알림만 보고 싶을 때 사용합니다.
     * 예: 채팅 알림만, 좋아요 알림만 필터링
     *
     * @param userId 사용자 ID
     * @param type 필터링할 알림 타입
     * @param pageable 페이지 정보
     * @return 특정 타입의 알림 목록
     *
     * 사용 시나리오:
     * - 알림 설정에서 타입별 미리보기
     * - 특정 타입 알림만 관리하고 싶을 때
     *
     */

//    @Query("""
//        SELECT new com.example.onlyone.domain.notification.dto.NotificationListItem(
//            n.notificationId,
//            n.content,
//            n.notificationType.type,
//            n.isRead,
//            n.createdAt
//        )
//        FROM Notification n
//        WHERE n.user.userId = :userId
//          AND n.notificationType.type = :type
//        ORDER BY n.notificationId DESC
//        """)
//    List<NotificationListItem> findByUserAndType(
//        @Param("userId") Long userId,
//        @Param("type") Type type,
//        Pageable pageable);

    /**
     * 읽지 않은 알림만 조회 (프로젝션 사용) (예정)
     *
     * 사용자가 읽지 않은 알림만 확인하고 싶을 때 사용합니다.
     * 알림 관리나 일괄 읽음 처리 전 미리보기에 활용됩니다.
     *
     * @param userId 사용자 ID
     * @param pageable 페이지 정보
     * @return 읽지 않은 알림 목록
     *
     * 사용 시나리오:
     * - "읽지 않음" 필터 적용 시
     * - 알림 요약 화면
     */
//    @Query("""
//        SELECT new com.example.onlyone.domain.notification.dto.NotificationListItem(
//            n.notificationId,
//            n.content,
//            n.notificationType.type,
//            n.isRead,
//            n.createdAt
//        )
//        FROM Notification n
//        WHERE n.user.userId = :userId
//          AND n.isRead = false
//        ORDER BY n.notificationId DESC
//        """)
//    List<NotificationListItem> findUnreadByUser(
//        @Param("userId") Long userId,
//        Pageable pageable);


    // ================================
    // 업데이트/삭제 메서드들 (벌크 연산)
    // ================================

    /**
     * 특정 알림들을 읽음 처리 (벌크 업데이트)
     *
     * 사용자가 선택한 여러 알림을 한 번에 읽음 처리합니다.
     * 벌크 연산으로 성능을 최적화했으며, 보안을 위해 사용자 ID도 확인합니다.
     * 이미 읽은 알림은 제외하여 불필요한 업데이트를 방지합니다.
     *
     * @param userId 사용자 ID (권한 검증용)
     * @param notificationIds 읽음 처리할 알림 ID 목록
     * @return 실제 업데이트된 알림 개수
     *
     * 사용 시나리오:
     * - 사용자가 여러 알림을 선택하여 읽음 처리
     * - 알림 상세 조회 시 자동 읽음 처리
     *
     * 보안 고려사항:
     * - 다른 사용자의 알림은 업데이트되지 않음
     * - 이미 읽은 알림은 재처리하지 않음
     */
//    @Modifying
//    @Query("""
//        UPDATE Notification n
//        SET n.isRead = true
//        WHERE n.notificationId IN :notificationIds
//          AND n.user.userId = :userId
//          AND n.isRead = false
//        """)
//    int markAsReadByIds(
//        @Param("userId") Long userId,
//        @Param("notificationIds") List<Long> notificationIds);

    /**
     * 사용자의 모든 알림을 읽음 처리 (벌크 업데이트)
     *
     * "모두 읽음" 기능 구현을 위한 메서드입니다.
     * 해당 사용자의 모든 읽지 않은 알림을 한 번에 처리합니다.
     *
     * @param userId 사용자 ID
     * @return 실제 업데이트된 알림 개수
     *
     * 사용 시나리오:
     * - "모든 알림 읽음" 버튼 클릭
     * - 알림 화면 종료 시 자동 전체 읽음 처리
     *
     * 성능 주의사항:
     * - 대량의 알림이 있는 사용자의 경우 시간이 오래 걸릴 수 있음
     * - 배치 크기 제한이나 비동기 처리 고려 필요
     */
//    @Modifying
//    @Query("""
//        UPDATE Notification n
//        SET n.isRead = true
//        WHERE n.user.userId = :userId
//          AND n.isRead = false
//        """)
//    int markAllAsReadByUserId(@Param("userId") Long userId);

    /**
     * 오래된 알림 삭제 (배치 작업용)
     *
     * 정기적으로 실행되는 배치 작업에서 오래된 알림을 삭제합니다.
     * 데이터베이스 용량 관리와 성능 유지를 위한 필수 작업입니다.
     *
     * @param cutoffDate 삭제 기준 날짜 (이 날짜 이전의 알림 삭제)
     * @return 삭제된 알림 개수
     *
     * 사용 시나리오:
     * - 매일 자정에 6개월 이전 알림 삭제
     * - 월 단위로 1년 이전 알림 삭제
     *
     * 배치 작업 고려사항:
     * - 한 번에 너무 많은 데이터 삭제 시 성능 영향
     * - 배치 크기를 나누어 점진적 삭제 권장
     * - 삭제 전 중요한 알림은 별도 보관 고려
     */
//    @Modifying
//    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoffDate")
//    int deleteOldNotifications(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * 특정 사용자의 알림 중 특정 ID 목록에 해당하는 것들만 조회
     *
     * 사용자가 선택한 특정 알림들의 상세 정보를 조회할 때 사용합니다.
     * 권한 검증과 함께 필요한 알림만 정확히 조회합니다.
     *
     * @param userId 사용자 ID (권한 검증용)
     * @param notificationIds 조회할 알림 ID 목록
     * @return 해당하는 알림 엔티티 목록
     *
     * 사용 시나리오:
     * - 선택된 알림들의 읽음 처리 전 검증
     * - 특정 알림들의 FCM 전송 상태 확인
     * - 알림 삭제 전 권한 및 존재 여부 확인
     *
     * 보안 고려사항:
     * - 다른 사용자의 알림은 조회되지 않음
     * - 존재하지 않는 알림 ID는 결과에서 제외됨
     */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.user.userId = :userId
          AND n.notificationId IN :notificationIds
        """)
    List<Notification> findByUserIdAndIds(
        @Param("userId") Long userId,
        @Param("notificationIds") List<Long> notificationIds);

    // ================================
    // 향후 확장 가능한 메서드들 (주석으로 기록)
    // ================================

    /**
     * FCM 전송이 실패한 알림들 조회 (재전송용)
     * @param userId 사용자 ID
     * @param limit 조회 제한 개수
     * @return FCM 전송 실패 알림 목록
     */
    // @Query("SELECT n FROM Notification n WHERE n.user.userId = :userId AND n.fcmSent = false ORDER BY n.createdAt ASC")
    // List<Notification> findFailedFcmNotifications(@Param("userId") Long userId, Pageable pageable);

    /**
     * 특정 기간 내 알림 통계 조회
     * @param userId 사용자 ID
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 기간별 알림 통계
     */
    // @Query("SELECT n.notificationType.type, COUNT(n) FROM Notification n WHERE n.user.userId = :userId AND n.createdAt BETWEEN :startDate AND :endDate GROUP BY n.notificationType.type")
    // List<Object[]> getNotificationStatistics(@Param("userId") Long userId, @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
}