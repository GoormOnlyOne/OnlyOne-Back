package com.example.onlyone.global.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ErrorDto {

    private String timestamp;
    private int status;
    private String code;
    private String message;
    private String path;
}
