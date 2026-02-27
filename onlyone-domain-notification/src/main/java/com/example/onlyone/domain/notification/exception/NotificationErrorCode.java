package com.example.onlyone.domain.notification.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_TYPE_NOT_FOUND(404, "NOTIFY_404_1", "알림 타입을 찾을 수 없습니다."),
    NOTIFICATION_NOT_FOUND(404, "NOTIFY_404_2", "알림이 존재하지 않습니다."),
    INVALID_EVENT_ID(400, "NOTIFY_400_2", "유효하지 않은 이벤트 ID입니다."),
    INVALID_NOTIFICATION_DATA(400, "NOTIFY_400_3", "유효하지 않은 알림 데이터입니다."),
    UNREAD_COUNT_UPDATE_FAILED(500, "NOTIFY_500_1", "읽지 않은 알림 개수 업데이트에 실패했습니다."),
    NOTIFICATION_PROCESSING_FAILED(500, "NOTIFY_500_4", "알림 처리 중 오류가 발생했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
