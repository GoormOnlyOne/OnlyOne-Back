package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationTypeRepository typeRepo;
    private final NotificationRepository notificationRepo;
    private final ApplicationEventPublisher eventPublisher;

    // 알림을 생성하고 저장한다.
    @Transactional
    public Notification sendNotification(User to, Type type, String... args){

        // 알림 유형이 존재하는지 검사
        NotificationType nt = typeRepo.findByType(type)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));

        // 알림 유형이 존재하면 알림 생성
        Notification notification = Notification.create(to, nt, args);

        // 알림 저장
        Notification saved = notificationRepo.save(notification);

        // 저장된 알림을 이벤트로 발행
        eventPublisher.publishEvent(new NotificationCreatedEvent(saved));
        return saved;
    }

    // 읽음 처리
    @Transactional
    public void markAsRead(Long id) {

        // 알림 존재하는지 확인
        Notification n = notificationRepo.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));

        // 알림이 존재하면 읽음 상태로 변경
        n.markAsRead();
    }

}
