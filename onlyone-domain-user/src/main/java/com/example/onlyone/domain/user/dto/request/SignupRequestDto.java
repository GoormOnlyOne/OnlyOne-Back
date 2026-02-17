package com.example.onlyone.domain.user.dto.request;

import com.example.onlyone.domain.user.entity.Gender;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record SignupRequestDto(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 10, message = "닉네임은 2자 이상 10자 이하로 입력해주세요.")
        @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "닉네임은 특수문자를 제외한 한글, 영문, 숫자만 가능합니다.")
        String nickname,

        @NotNull(message = "생년월일은 필수입니다.")
        @Past(message = "생년월일은 과거 날짜여야 합니다.")
        LocalDate birth,

        @NotNull(message = "성별은 필수입니다.")
        Gender gender,

        String profileImage,

        @NotBlank(message = "도시는 필수입니다.")
        @Size(max = 20, message = "도시를 선택해주세요.")
        String city,

        @NotBlank(message = "구/군은 필수입니다.")
        @Size(max = 20, message = "구/군명을 선택해주세요.")
        String district,

        @NotNull(message = "관심사는 최소 1개 이상 최대 5개 이하로 선택해야 합니다.")
        List<String> categories
) {
}
