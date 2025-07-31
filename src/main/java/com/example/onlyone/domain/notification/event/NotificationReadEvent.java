package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.entity.Notification;
import lombok.Getter;

@Getter
public class NotificationReadEvent {

  private final Notification notification;

  public NotificationReadEvent(Notification notification) {
    this.notification = notification;
  }
}