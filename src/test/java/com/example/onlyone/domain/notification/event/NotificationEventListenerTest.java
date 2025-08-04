package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService; // 변경: SseEmitterService → NotificationService

    @InjectMocks
    private NotificationEventListener listener;

    @Test
    @DisplayName("NotificationCreatedEvent 수신 시 sendCreated() 호출")
    void onNotificationCreated() {
        // given
        Notification mockNotification = Mockito.mock(Notification.class);
        NotificationCreatedEvent event = new NotificationCreatedEvent(mockNotification);

        // when
        listener.onNotificationCreated(event);

        // then
        then(notificationService).should()
            .sendCreated(mockNotification);
    }

    @Test
    @DisplayName("NotificationReadEvent 수신 시 sendRead() 호출")
    void onNotificationRead() {
        // given
        Notification mockNotification = Mockito.mock(Notification.class);
        NotificationReadEvent event = new NotificationReadEvent(mockNotification);

        // when
        listener.onNotificationRead(event);

        // then
        then(notificationService).should()
            .sendRead(mockNotification);
    }
}