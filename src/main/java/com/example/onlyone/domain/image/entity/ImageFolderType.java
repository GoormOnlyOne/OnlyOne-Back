package com.example.onlyone.domain.image.entity;

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

    public static ImageFolderType fromString(String type) {
        for (ImageFolderType imageFolderType : ImageFolderType.values()) {
            if (imageFolderType.name().equalsIgnoreCase(type)) {
                return imageFolderType;
            }
        }
        return null;
    }
}