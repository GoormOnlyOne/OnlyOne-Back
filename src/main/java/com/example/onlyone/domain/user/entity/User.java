package com.example.onlyone.domain.user.entity;

import com.example.onlyone.global.BaseTimeEntity;
import com.fasterxml.jackson.databind.ser.Serializers;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.*;

@Entity
@Table(name = "user")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "kakao_id", updatable = false, unique = true)
    @NotNull
    private Long kakaoId;

    @Column(name = "nickname")
    private String nickname;

    @Column(name = "birth")
    private LocalDate birth;

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "profile_image")
    private String profileImage;

    @Column(name = "gender")
    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "city")
    private String city;

    @Column(name = "district")
    private String district;

    @Column(name = "fcm_token")
    private String fcmToken;

    public void update(String city, String district, String profileImage, String nickname, Gender gender, LocalDate birth) {
        this.city = city;
        this.district = district;
        this.profileImage = profileImage;
        this.nickname = nickname;
        this.gender = gender;
        this.birth = birth;
    }
}