package com.example.onlyone.domain.notification.entity;

import lombok.Getter;

/**
 * 알림 타입 열거형
 *
 * 시스템에서 지원하는 모든 알림 종류를 정의합니다.
 * 각 타입은 템플릿과 클릭 시 이동할 타겟 타입을 정의합니다.
 */
@Getter
public enum NotificationType {
    CHAT("CHAT", "%s님이 메시지를 보냈습니다"),
    SETTLEMENT("SETTLEMENT", "%s 정산이 완료되었습니다"),
    LIKE("POST", "%s님이 회원님의 게시글을 좋아합니다"),
    COMMENT("POST", "%s님이 댓글을 남겼습니다"),
    REFEED("FEED", "%s님이 회원님의 피드를 리피드했습니다");

    private final String targetType;
    private final String template;

    NotificationType(String targetType, String template) {
        this.targetType = targetType;
        this.template = template;
    }

    /**
     * 템플릿에 인자를 적용하여 최종 메시지 생성
     */
    public String render(String name) {
        if (name == null || name.isEmpty()) {
            return template.replace("%s", "");
        }
        return String.format(template, name);
    }
}
