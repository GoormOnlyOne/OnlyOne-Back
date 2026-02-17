package com.example.onlyone.global.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Schema(description = "API 공통 응답 형식")
public class CommonResponse<T>{

    @Schema(description = "요청 처리 성공 여부", example = "true")
    private boolean success;

    @Schema(description = "응답 데이터")
    private T data;

    public static <T> CommonResponse<T> success(T data){
        return CommonResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> CommonResponse<T> error(T data){
        return CommonResponse.<T>builder()
                .success(false)
                .data(data)
                .build();
    }
}
