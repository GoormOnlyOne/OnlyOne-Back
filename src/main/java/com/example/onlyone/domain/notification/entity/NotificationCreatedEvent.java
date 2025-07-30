package com.example.onlyone.domain.notification.entity;

import lombok.Getter;

@Getter
public class NotificationCreatedEvent {

    private final Notification notification;

    public NotificationCreatedEvent(Notification notification) {
        this.notification = notification;
    }
}
