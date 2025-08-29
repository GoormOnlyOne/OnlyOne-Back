package com.example.onlyone.domain.notification.dto.request;

import com.example.onlyone.domain.notification.entity.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배치 알림 생성 요청 DTO
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BatchNotificationRequestDto {
    
    private Long userId;
    private Type type;
    private String[] args;
    
    public static BatchNotificationRequestDto of(Long userId, Type type, String... args) {
        return BatchNotificationRequestDto.builder()
                .userId(userId)
                .type(type)
                .args(args)
                .build();
    }
}