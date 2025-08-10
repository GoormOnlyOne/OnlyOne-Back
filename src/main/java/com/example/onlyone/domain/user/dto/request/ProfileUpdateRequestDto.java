package com.example.onlyone.domain.user.dto.request;

import com.example.onlyone.domain.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class ProfileUpdateRequestDto {
    
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 2, max = 10, message = "닉네임은 2자 이상 10자 이하여야 합니다.")
    @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "닉네임은 한글, 영문, 숫자만 사용 가능합니다.")
    private String nickname;
    
    @NotNull(message = "생년월일은 필수입니다.")
    private LocalDate birth;
    
    private String profileImage;
    
    @NotNull(message = "성별은 필수입니다.")
    private Gender gender;
    
    @NotBlank(message = "시/도는 필수입니다.")
    private String city;
    
    @NotBlank(message = "구/군은 필수입니다.")
    private String district;
    
    @NotNull(message = "관심사는 필수입니다.")
    private List<String> interestsList;
}