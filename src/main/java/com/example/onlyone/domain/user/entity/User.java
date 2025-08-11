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

  @Column(name = "kakao_access_token")
  private String kakaoAccessToken;

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

  public void updateKakaoAccessToken(String kakaoAccessToken) {
    this.kakaoAccessToken = kakaoAccessToken;
  }

  public void clearKakaoAccessToken() {
    this.kakaoAccessToken = null;
  }

  public void withdraw() {
    this.status = Status.INACTIVE;
    this.kakaoAccessToken = null;
  }

  public void completeSignup() {
    this.status = Status.ACTIVE;
  }

  /**
   * FCM 토큰 형식 유효성 검증 (MVP용 - 최소한의 검증)
   * 실제 Firebase 전송 결과로 유효성 최종 판단
   */
  private void validateFcmTokenFormat(String fcmToken) {
    if (fcmToken == null || fcmToken.isBlank()) {
      throw new IllegalArgumentException("FCM token cannot be null or empty");
    }

    // MVP 단계에서는 null/빈값 체크만 수행
    // 길이, 패턴 검증은 실제 FCM 전송 결과로 판단
    // 프로덕션에서는 실제 토큰 샘플 기반으로 검증 로직 추가 예정
  }
}