package com.example.onlyone.domain.notification.repository;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationListItem;
import com.example.onlyone.domain.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
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
     * @param userId 사용자 ID
     * @return 읽지 않은 알림 엔티티 목록
     */
    List<Notification> findByUser_UserIdAndIsReadFalse(Long userId);

    /**
     * 사용자의 읽지 않은 알림 개수 조회
     *
     * @param userId 사용자 ID
     * @return 읽지 않은 알림 개수
     */
    long countByUser_UserIdAndIsReadFalse(Long userId);

    /**
     * 첫 페이지 조회 (NotificationListItem 프로젝션 사용)
     * <p>
     * 알림 목록의 첫 페이지를 조회합니다. 프로젝션을 사용하여 필요한 필드만 조회합니다. notification_id DESC 정렬로 최신 알림이 먼저 표시됩니다.
     *
     * @param userId   사용자 ID
     * @param pageable 페이지 정보 (주로 size만 사용)
     * @return 알림 목록 (프로젝션)
     * <p>
     * 사용 시나리오: - 앱 실행 시 최신 알림 목록 로드 - 알림 화면 진입 시 초기 데이터 표시
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
     * 커서 기반 페이징의 핵심 메서드입니다. 커서(이전 페이지의 마지막 notification_id)보다 작은 ID를 가진 알림들을 조회합니다.
     *
     * @param userId   사용자 ID
     * @param cursor   이전 페이지의 마지막 notification_id
     * @param pageable 페이지 정보 (size)
     * @return 커서 이후의 알림 목록 (프로젝션)
     *
     * 사용 시나리오: - 무한 스크롤 구현 - "더보기" 버튼 클릭 시 추가 데이터 로드
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
}






























