package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.Gender;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class MyPageResponse {
    
    private String nickname;
    
    @JsonProperty("profile_image")
    private String profileImage;
    
    private String city;
    
    private String district;
    
    private LocalDate birth;
    
    private Gender gender;
    
    @JsonProperty("interests_list")
    private List<String> interestsList;
    
    private Integer balance;
}