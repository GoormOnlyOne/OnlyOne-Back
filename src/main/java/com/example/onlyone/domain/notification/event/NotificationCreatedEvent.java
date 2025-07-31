package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.Notification;
import lombok.Getter;

@Getter
public class NotificationCreatedEvent {

  private final Notification notification;

  public NotificationCreatedEvent(Notification notification) {
    this.notification = notification;
  }
}
