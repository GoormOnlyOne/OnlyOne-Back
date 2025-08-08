package com.example.onlyone.domain.user.dto.request;

import com.example.onlyone.domain.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Getter
@NoArgsConstructor
public class SignupRequestDto {

    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 2, max = 10, message = "닉네임은 2자 이상 10자 이하로 입력해주세요.")
    @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "닉네임은 특수문자를 제외한 한글, 영문, 숫자만 가능합니다.")
    private String nickname;

    @NotNull(message = "생년월일은 필수입니다.")
    private LocalDate birth;

    @NotNull(message = "성별은 필수입니다.")
    private Gender gender;

    private String profileImage;

    @NotBlank(message = "도시는 필수입니다.")
    @Size(max = 20, message = "도시를 선택해주세요.")
    private String city;

    @NotBlank(message = "구/군은 필수입니다.")
    @Size(max = 20, message = "구/군명을 선택해주세요.")
    private String district;

    @NotNull(message = "관심사는 최소 1개 이상 최대 5개 이하로 선택해야 합니다.")
    private List<String> categories;
}