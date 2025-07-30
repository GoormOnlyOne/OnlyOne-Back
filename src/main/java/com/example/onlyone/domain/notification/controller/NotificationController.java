package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.NotificationListRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "알림")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;


    @Operation(summary = "알림 생성", description = "알림을 생성합니다")
    @PostMapping
    public ResponseEntity<?> sendNotification(
            @Valid @RequestBody NotificationRequestDto reqDto
    ) {

        NotificationResponseDto responseDto =
                notificationService.sendNotification(reqDto);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(CommonResponse.success(responseDto));
    }

    @Operation(summary = "전체 알림 읽음", description = "알림 조회시 전체 알림이 읽음 상태로 변합니다")
    @PatchMapping("/read-all")
    public ResponseEntity<Void> readAll(@RequestBody NotificationListRequestDto dto) {
        notificationService.markAllAsRead(dto);
        return ResponseEntity.noContent().build();
    }
}