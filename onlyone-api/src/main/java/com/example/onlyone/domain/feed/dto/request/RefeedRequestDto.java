package com.example.onlyone.domain.feed.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefeedRequestDto(
    @NotBlank
    @Size(max = 50, message = "피드 설명은 {max}자 이내여야 합니다.")
    String content
) {
}
