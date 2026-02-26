package com.example.onlyone.global.common.util;

public final class MessageUtils {
    public static final String IMAGE_PREFIX = "IMAGE::";
    public static final String IMAGE_PLACEHOLDER = "사진을 보냈습니다.";

    private MessageUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isImageMessage(String text) {
        return text != null && text.startsWith(IMAGE_PREFIX);
    }

    /**
     * IMAGE:: 프리픽스가 있으면 URL 부분만 추출, 아니면 null 반환
     */
    public static String extractImageUrl(String text) {
        if (!isImageMessage(text)) return null;
        return text.substring(IMAGE_PREFIX.length()).trim();
    }

    /** 이미지 URL 기본 형식 검증 (빈값/공백/쉼표 불가) */
    public static boolean isValidImageUrlFormat(String url) {
        return url != null && !url.isBlank() && !url.contains(",") && !url.contains(" ");
    }

    /** 이미지 확장자 검증 (png/jpg/jpeg만 허용) */
    public static boolean hasValidImageExtension(String url) {
        return url != null && url.matches("(?i).+\\.(png|jpg|jpeg)$");
    }

    public static String getDisplayText(String text) {
        return isImageMessage(text) ? IMAGE_PLACEHOLDER : text;
    }
}