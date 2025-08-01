package com.example.onlyone.domain.image.controller;

import com.example.onlyone.domain.image.dto.request.PresignedUrlRequestDto;
import com.example.onlyone.domain.image.dto.response.PresignedUrlResponseDto;
import com.example.onlyone.domain.image.entity.ImageFolderType;
import com.example.onlyone.domain.image.service.ImageService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Image", description = "이미지 업로드 API")
@RestController
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @Operation(summary = "Presigned URL 생성", description = "S3에 이미지 업로드를 위한 Presigned URL을 생성합니다.")
    @PostMapping("/{imageFolderType}/presigned-url")
    public ResponseEntity<CommonResponse<PresignedUrlResponseDto>> generatePresignedUrl(
            @PathVariable String imageFolderType,
            @Valid @RequestBody PresignedUrlRequestDto request) {

        PresignedUrlResponseDto response = imageService.generatePresignedUrlWithImageUrl(
                imageFolderType,
                request.getFileName(),
                request.getContentType(),
                request.getImageSize()
        );

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}