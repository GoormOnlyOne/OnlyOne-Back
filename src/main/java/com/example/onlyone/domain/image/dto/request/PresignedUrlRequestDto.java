package com.example.onlyone.domain.image.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PresignedUrlRequestDto {
    
    @NotBlank(message = "파일명은 필수입니다.")
    private String fileName;
    
    @NotBlank(message = "컨텐츠 타입은 필수입니다.")
    private String contentType;
}