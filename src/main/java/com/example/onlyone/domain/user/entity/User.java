package com.example.onlyone.domain.user.entity;

import com.example.onlyone.global.BaseTimeEntity;
import com.fasterxml.jackson.databind.ser.Serializers;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
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

  public void updateFcmToken(String fcmToken) {
    validateFcmTokenFormat(fcmToken);
    this.fcmToken = fcmToken;
  }

  public void clearFcmToken() {
    this.fcmToken = null;
  }

  public boolean hasFcmToken() {
    return fcmToken != null && !fcmToken.isBlank();
  }

  public void update(String city, String district, String profileImage, String nickname, Gender gender, LocalDate birth) {
    this.city = city;
    this.district = district;
    this.profileImage = profileImage;
    this.nickname = nickname;
    this.gender = gender;
    this.birth = birth;
  }

  /**
   * FCM 토큰 형식 유효성 검증
   */
  private void validateFcmTokenFormat(String fcmToken) {
    if (fcmToken == null || fcmToken.isBlank()) {
      throw new IllegalArgumentException("FCM token cannot be null or empty");
    }

    // FCM 토큰 길이 검증 (140-165자)
    if (fcmToken.length() < 140 || fcmToken.length() > 165) {
      throw new IllegalArgumentException("Invalid FCM token length");
    }

    // FCM 토큰 패턴 검증 (영문자, 숫자, 하이픈, 언더스코어, 콜론만 허용)
    if (!fcmToken.matches("^[a-zA-Z0-9_:-]+$")) {
      throw new IllegalArgumentException("Invalid FCM token format");
    }
  }
}