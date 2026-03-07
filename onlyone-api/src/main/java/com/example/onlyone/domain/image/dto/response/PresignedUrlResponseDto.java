package com.example.onlyone.domain.image.dto.response;

public record PresignedUrlResponseDto(
    String presignedUrl,
    String imageUrl
) {
}
