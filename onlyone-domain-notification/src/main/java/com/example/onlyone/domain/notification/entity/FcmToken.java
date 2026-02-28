package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FCM 디바이스 토큰 엔티티.
 * 사용자별 여러 디바이스 토큰을 관리한다.
 */
@Entity
@Table(name = "fcm_token", indexes = {
        @Index(name = "idx_fcm_token_user_id", columnList = "user_id"),
        @Index(name = "idx_fcm_token_token", columnList = "token", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FcmToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fcm_token_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Builder
    public FcmToken(Long userId, String token, String deviceType) {
        this.userId = userId;
        this.token = token;
        this.deviceType = deviceType;
    }
}
