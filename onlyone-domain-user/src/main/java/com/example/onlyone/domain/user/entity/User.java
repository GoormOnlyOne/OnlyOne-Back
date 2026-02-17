package com.example.onlyone.domain.user.entity;

import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;
import java.util.Objects;

@Entity
@Table(name = "`user`", indexes = {
    @Index(name = "idx_user_kakao_id", columnList = "kakao_id"),
    @Index(name = "idx_user_status", columnList = "status"),
    @Index(name = "idx_user_nickname", columnList = "nickname")
})
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


  @Column(name = "kakao_access_token")
  private String kakaoAccessToken;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false)
  @Builder.Default
  private Role role = Role.ROLE_USER;

  // ========== 비즈니스 메서드 ==========

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

  // ========== equals & hashCode ==========

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof User that)) return false;

    // userId가 null인 경우 (영속화 전)
    if (userId == null && that.userId == null) {
      return false;
    }

    return Objects.equals(userId, that.userId);
  }

  @Override
  public int hashCode() {
    return userId != null ? Objects.hash(userId) : getClass().hashCode();
  }

  @Override
  public String toString() {
    return String.format("User{userId=%d, kakaoId=%d, nickname='%s', status=%s}",
        userId, kakaoId, nickname, status);
  }
}