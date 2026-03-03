package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.MessageCommandService;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.chat.exception.ChatErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@ConditionalOnProperty(name = "app.chat.websocket", havingValue = "stomp", matchIfMissing = true)
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final UserService userService;
    private final AsyncMessageService asyncMessageService;
    private final MessageCommandService messageCommandService;

    @MessageMapping("/chat/{chatRoomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal principal = extractPrincipal(headerAccessor);
        User user = userService.getMemberById(principal.getUserId());

        messageCommandService.publishImmediately(
                chatRoomId, user.getUserId(), user.getNickname(),
                user.getProfileImage(), request.text());

        asyncMessageService.saveMessageAsync(chatRoomId, user.getUserId(), request.text());
    }

    @MessageExceptionHandler(CustomException.class)
    @SendToUser("/sub/errors")
    public String handleCustomException(CustomException ex) {
        log.warn("[WebSocket.Error] code={}, message={}", ex.getErrorCode(), ex.getMessage());
        return ex.getErrorCode().getMessage();
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/sub/errors")
    public String handleException(Exception ex) {
        log.error("[WebSocket.Error] unexpected", ex);
        return ChatErrorCode.MESSAGE_SERVER_ERROR.getMessage();
    }

    private UserPrincipal extractPrincipal(SimpMessageHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new CustomException(ChatErrorCode.UNAUTHORIZED_CHAT_ACCESS);
        }
        return (UserPrincipal)
                ((UsernamePasswordAuthenticationToken) accessor.getUser()).getPrincipal();
    }
}
