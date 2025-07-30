package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.NotificationListRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.common.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

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


    @PatchMapping("/read-all")
    public ResponseEntity<Void> readAll(@RequestBody NotificationListRequestDto dto) {
        notificationService.markAllAsRead(dto);
        return ResponseEntity.noContent().build();
    }
}