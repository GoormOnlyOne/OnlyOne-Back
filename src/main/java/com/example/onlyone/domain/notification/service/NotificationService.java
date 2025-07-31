package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.NotificationListRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserRepository userRepo;
    private final NotificationTypeRepository typeRepo;
    private final NotificationRepository notificationRepo;
    private final ApplicationEventPublisher eventPublisher;

    // 알림 생성
    @Transactional
    public NotificationResponseDto sendNotification(NotificationRequestDto dto) {
        // 1) User 조회
        User toUser = userRepo.findById(dto.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // (2) NotificationType 조회
        NotificationType nt = typeRepo.findByType(dto.getType())
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));

        // 3) 알림 생성·저장
        Notification notification = Notification.create(toUser, nt, dto.getArgs());
        Notification saved = notificationRepo.save(notification);

        // 4) 이벤트 발행
        eventPublisher.publishEvent(new NotificationCreatedEvent(saved));

        // 5) 엔티티 → DTO 변환 후 반환
        return NotificationResponseDto.fromEntity(saved);
    }

    // 읽음 처리
    @Transactional
    public void markAllAsRead(NotificationListRequestDto dto) {
        // 1) 미읽음 알림 조회
        List<Notification> unreadList =
                notificationRepo.findByUser_UserIdAndIsReadFalse(dto.getUserId());

        // 2) 순회하면서 markAsRead 호출 (dirty‐checking)
        unreadList.forEach(Notification::markAsRead);
    }
}