package com.example.onlyone.global.sse.dto;

import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * SSE 인증용 사용자 캐시 DTO - Redis 직렬화 최적화
 */
@Getter
@Builder
public class UserCacheDto {
    
    private final Long userId;
    private final Long kakaoId;
    private final String nickname;
    private final Status status;
    private final String profileImage;
    private final Gender gender;
    private final String city;
    private final String district;
    private final LocalDate birth;
    
    @JsonCreator
    public UserCacheDto(
            @JsonProperty("userId") Long userId,
            @JsonProperty("kakaoId") Long kakaoId,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("status") Status status,
            @JsonProperty("profileImage") String profileImage,
            @JsonProperty("gender") Gender gender,
            @JsonProperty("city") String city,
            @JsonProperty("district") String district,
            @JsonProperty("birth") LocalDate birth) {
        this.userId = userId;
        this.kakaoId = kakaoId;
        this.nickname = nickname;
        this.status = status;
        this.profileImage = profileImage;
        this.gender = gender;
        this.city = city;
        this.district = district;
        this.birth = birth;
    }
    
    public static UserCacheDto from(User user) {
        return UserCacheDto.builder()
                .userId(user.getUserId())
                .kakaoId(user.getKakaoId())
                .nickname(user.getNickname())
                .status(user.getStatus())
                .profileImage(user.getProfileImage())
                .gender(user.getGender())
                .city(user.getCity())
                .district(user.getDistrict())
                .birth(user.getBirth())
                .build();
    }
    
    public User toUser() {
        return User.builder()
                .userId(userId)
                .kakaoId(kakaoId)
                .nickname(nickname)
                .status(status)
                .profileImage(profileImage)
                .gender(gender)
                .city(city)
                .district(district)
                .birth(birth)
                .build();
    }
}