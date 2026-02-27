package com.example.onlyone.domain.image.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ImageErrorCode implements ErrorCode {

    INVALID_IMAGE_FOLDER_TYPE(400, "IMAGE_400_1", "유효하지 않은 이미지 폴더 타입입니다."),
    INVALID_IMAGE_CONTENT_TYPE(400, "IMAGE_400_2", "유효하지 않은 이미지 컨텐츠 타입입니다."),
    INVALID_IMAGE_SIZE(400, "IMAGE_400_3", "유효하지 않은 이미지 크기입니다."),
    IMAGE_SIZE_EXCEEDED(413, "IMAGE_413_1", "이미지 크기가 허용된 최대 크기(5MB) 크기를 초과했니다."),
    IMAGE_UPLOAD_FAILED(500, "IMAGE_500_1", "이미지 업로드 중 오류가 발생했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
