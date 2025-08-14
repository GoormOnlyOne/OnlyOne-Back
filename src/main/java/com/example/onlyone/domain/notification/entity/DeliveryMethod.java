package com.example.onlyone.domain.notification.entity;

public enum DeliveryMethod {
    FCM_ONLY(true, false),
    SSE_ONLY(false, true),
    BOTH(true, true);

    private final boolean shouldSendFcm;
    private final boolean shouldSendSse;

    DeliveryMethod(boolean shouldSendFcm, boolean shouldSendSse) {
        this.shouldSendFcm = shouldSendFcm;
        this.shouldSendSse = shouldSendSse;
    }

    public static DeliveryMethod getOptimalMethod(Type notificationType) {
        return switch (notificationType) {
            case CHAT, SETTLEMENT -> FCM_ONLY;
            case LIKE, COMMENT, REFEED -> SSE_ONLY;
        };
    }

    public boolean shouldSendFcm() {
        return shouldSendFcm;
    }

    public boolean shouldSendSse() {
        return shouldSendSse;
    }
}