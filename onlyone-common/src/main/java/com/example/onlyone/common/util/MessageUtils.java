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
     * IMAGE:: 프리픽스가 있으면 URL 부분만 추출, 아니면 원본 반환
     */
    public static String extractImageUrl(String text) {
        if (!isImageMessage(text)) return null;
        return text.substring(IMAGE_PREFIX.length()).trim();
    }

    public static String getDisplayText(String text) {
        return isImageMessage(text) ? IMAGE_PLACEHOLDER : text;
    }
}