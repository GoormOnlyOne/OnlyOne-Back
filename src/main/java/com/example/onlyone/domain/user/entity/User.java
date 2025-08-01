package com.example.onlyone.domain.user.entity;

import com.example.onlyone.global.BaseTimeEntity;
import com.fasterxml.jackson.databind.ser.Serializers;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.*;

@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "kakao_id", updatable = false, unique = true)
    @NotNull
    private Long kakaoId;

    @Column(name = "nickname")
    @NotNull
    private String nickname;

    @Column(name = "birth")
    @NotNull
    private LocalDate birth;

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "profile_image")
    private String profileImage;

    @Column(name = "gender")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "district")
    private String district;

    @Column(name = "fcm_token")
    private String fcmToken;
}