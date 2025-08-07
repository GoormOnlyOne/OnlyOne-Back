package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AppNotificationEventListenerTest {

    @Mock
    private NotificationService notificationService; // 변경: SseEmitterService → NotificationService

    @InjectMocks
    private NotificationEventListener listener;

    @Test
    @DisplayName("NotificationCreatedEvent 수신 시 sendCreated() 호출")
    void onNotificationCreated() {
        // given
        AppNotification mockAppNotification = Mockito.mock(AppNotification.class);
        NotificationCreatedEvent event = new NotificationCreatedEvent(mockAppNotification);

        // when
        listener.onNotificationCreated(event);

        // then
        then(notificationService).should()
            .sendCreated(mockAppNotification);
    }

    @Test
    @DisplayName("NotificationReadEvent 수신 시 sendRead() 호출")
    void onNotificationRead() {
        // given
        AppNotification mockAppNotification = Mockito.mock(AppNotification.class);
        NotificationReadEvent event = new NotificationReadEvent(mockAppNotification);

        // when
        listener.onNotificationRead(event);

        // then
        then(notificationService).should()
            .sendRead(mockAppNotification);
    }
}