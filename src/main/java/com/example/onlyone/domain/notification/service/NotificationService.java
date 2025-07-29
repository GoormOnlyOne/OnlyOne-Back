package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
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
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 2) Type 변환
        Type type = Type.from(dto.getTypeCode());

        // 3) NotificationType 조회
        NotificationType nt = typeRepo.findByType(type)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));

        // 4) 알림 생성·저장
        Notification notification = Notification.create(toUser, nt, dto.getArgs());
        Notification saved = notificationRepo.save(notification);

        // 5) 이벤트 발행
        eventPublisher.publishEvent(new NotificationCreatedEvent(saved));

        // 6) 엔티티 → DTO 변환 후 반환
        return NotificationResponseDto.fromEntity(saved);
    }

    // 읽음 처리
    @Transactional
    public void markAsRead(Long id) {
        Notification n = notificationRepo.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
        n.markAsRead();
    }
}