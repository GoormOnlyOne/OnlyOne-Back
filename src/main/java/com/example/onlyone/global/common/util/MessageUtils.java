package com.example.onlyone.global.common.util;

public final class MessageUtils {
    public static final String IMAGE_PREFIX = "[IMAGE]";
    public static final String IMAGE_PLACEHOLDER = "사진을 보냈습니다.";

    private MessageUtils() {
        throw new UnsupportedOperationException("Utility class");
    }
    public static boolean isImageMessage(String text) {
        return text != null && text.startsWith(IMAGE_PREFIX);
    }

    public static String getDisplayText(String text) {
        return isImageMessage(text) ? IMAGE_PLACEHOLDER : text;
    }
}