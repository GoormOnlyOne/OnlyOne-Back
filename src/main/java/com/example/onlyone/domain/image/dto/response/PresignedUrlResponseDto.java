package com.example.onlyone.domain.image.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PresignedUrlResponseDto {
    
    private String presignedUrl;
    private String imageUrl;
}