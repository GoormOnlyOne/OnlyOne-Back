package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.AppNotification;
import lombok.Getter;

@Getter
public class NotificationReadEvent {

  private final AppNotification appNotification;

  public NotificationReadEvent(AppNotification appNotification) {
    this.appNotification = appNotification;
  }
}