package com.example.onlyone.domain.image.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PresignedUrlRequestDto {
    
    @NotBlank(message = "파일명은 필수입니다.")
    private String fileName;
    
    @NotBlank(message = "컨텐츠 타입은 필수입니다.")
    private String contentType;

    @NotNull(message = "이미지 크기는 필수입니다.")
    @Min(value = 1, message = "이미지 크기는 1바이트 이상이어야 합니다.")
    @Max(value = 5242880, message = "이미지 크기는 5MB 이하여야 합니다.")
    private Long imageSize;
}