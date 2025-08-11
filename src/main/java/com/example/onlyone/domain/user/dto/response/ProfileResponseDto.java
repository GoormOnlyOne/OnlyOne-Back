package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponseDto {
    private Long userId;
    private String nickname;
    private LocalDate birth;
    private String profileImage;
    private Gender gender;
    private String city;
    private String district;
    private List<String> interestsList;
}