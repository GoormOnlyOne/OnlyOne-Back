package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.ChatPublisher;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final UserRepository userRepository;
    private final AsyncMessageService asyncMessageService;
    private final ChatPublisher chatPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 메시지 수신 → 전송 우선, DB 저장은 비동기
     */
    @MessageMapping("/chat/{chatRoomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageRequest request) {

        log.info("🔥 WebSocket 메시지 수신: userId={}, text={}", request.getUserId(), request.getText());

        try {
            // 1. 유저 조회 (닉네임/프로필 표시용)
            User user = userRepository.findByKakaoId(request.getUserId())
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

            // 2. 전송 DTO 즉시 생성 (닉네임/프로필 포함)
            ChatMessageResponse response = ChatMessageResponse.builder()
                    .chatRoomId(chatRoomId)
                    .senderId(user.getUserId())
                    .senderNickname(user.getNickname())
                    .profileImage(user.getProfileImage())
                    .text(request.getText().startsWith("IMAGE::") ? null : request.getText())
                    .imageUrl(request.getText().startsWith("IMAGE::") ? request.getText().substring("IMAGE::".length()).trim() : null)
                    .sentAt(LocalDateTime.now())
                    .deleted(false)
                    .build();

            String payload = objectMapper.writeValueAsString(response);
            chatPublisher.publish(chatRoomId, payload);

            // 4. DB 저장은 비동기 처리
            asyncMessageService.saveMessageAsync(chatRoomId, request);

            log.info("✅ 메시지 Redis 발행 완료 (userId={}, chatRoomId={})", request.getUserId(), chatRoomId);

        } catch (CustomException e) {
            log.error("❌ CustomException: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ 처리 중 알 수 없는 예외 발생", e);
            throw new CustomException(ErrorCode.MESSAGE_SERVER_ERROR);
        }
    }

    /**
     * WebSocket 메시지 처리 중 예외 발생 시 클라이언트에게 전송
     */
    @MessageExceptionHandler(CustomException.class)
    @SendToUser("/sub/errors")
    public String handleCustomException(CustomException ex) {
        return ex.getErrorCode().getMessage();
    }

}