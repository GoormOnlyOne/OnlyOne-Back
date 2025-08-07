package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.AppNotification;
import lombok.Getter;

@Getter
public class NotificationCreatedEvent {

  private final AppNotification appNotification;

  public NotificationCreatedEvent(AppNotification appNotification) {
    this.appNotification = appNotification;
  }
}
