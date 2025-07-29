package com.example.onlyone.domain.chat.dto;

public class ErrorMessage {
    private String code;
    private String message;

    public ErrorMessage(String code, String message) {
        this.code = code;
        this.message = message;
    }
    // getters/setters 생략
}
