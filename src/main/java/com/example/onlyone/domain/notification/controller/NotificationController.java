package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> sendNotification(@Valid @RequestBody NotificationRequestDto reqDto) {

        // user_id로 유저 조회
        User toUser = userService.getMemberById(reqDto.getUserId());

        // enum 변환
        Type type = Type.valueOf(reqDto.getTypeCode());

        // 알림 생성
        Notification saved = notificationService.sendNotification(toUser, type, reqDto.getContent());

        // Entity -> Dto
        NotificationResponseDto data = NotificationResponseDto.fromEntity(saved);

        // 응답 포맷
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }


}
