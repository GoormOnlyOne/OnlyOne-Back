package com.example.onlyone.domain.image.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ImageFolderType {
    USER("user", "프로필 이미지"),
    CHAT("chat", "채팅 이미지"),
    FEED("feed", "피드 이미지"),
    CLUB("club", "클럽 이미지");

    private final String folder;
    private final String description;

    public static ImageFolderType from(String type) {
        for (ImageFolderType value : values()) {
            if (value.name().equalsIgnoreCase(type)) {
                return value;
            }
        }
        throw new CustomException(ErrorCode.INVALID_IMAGE_FOLDER_TYPE);
    }
}