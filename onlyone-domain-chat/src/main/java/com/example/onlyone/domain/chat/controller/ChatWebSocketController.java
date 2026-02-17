package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.ChatPublisher;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
     * Principal에서 인증된 kakaoId를 추출하여 사용 (클라이언트 body의 userId 무시)
     */
    @MessageMapping("/chat/{chatRoomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {

        // 인증된 Principal에서 kakaoId 추출 (JWT에서 검증된 값)
        UserPrincipal principal = (UserPrincipal)
                ((UsernamePasswordAuthenticationToken) headerAccessor.getUser()).getPrincipal();
        Long kakaoId = principal.getKakaoId();

        log.info("[WebSocket.Receive] message received: kakaoId={}, text={}", kakaoId, request.text());

        try {
            // 1. 유저 조회 (닉네임/프로필 표시용) - 인증된 kakaoId 사용
            User user = userRepository.findByKakaoId(kakaoId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

            // 2. 전송 DTO 즉시 생성 (닉네임/프로필 포함)
            ChatMessageResponse response = new ChatMessageResponse(
                    null,
                    chatRoomId,
                    user.getUserId(),
                    user.getNickname(),
                    user.getProfileImage(),
                    request.text().startsWith("IMAGE::") ? null : request.text(),
                    request.text().startsWith("IMAGE::") ? request.text().substring("IMAGE::".length()).trim() : null,
                    LocalDateTime.now(),
                    false
            );

            String payload = objectMapper.writeValueAsString(response);
            chatPublisher.publish(chatRoomId, payload);

            // 4. DB 저장은 비동기 처리 - 인증된 kakaoId로 request 재생성
            ChatMessageRequest authenticatedRequest = new ChatMessageRequest(kakaoId, request.text(), request.imageUrl());
            asyncMessageService.saveMessageAsync(chatRoomId, authenticatedRequest);

            log.info("[WebSocket.Publish] message published to Redis: kakaoId={}, chatRoomId={}", kakaoId, chatRoomId);

        } catch (CustomException e) {
            log.error("[WebSocket.Error] custom exception: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[WebSocket.Error] unexpected exception", e);
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