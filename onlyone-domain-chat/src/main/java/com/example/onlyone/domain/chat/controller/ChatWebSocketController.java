package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.MessageCommandService;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        log.info("[WebSocket.Receive] chatRoomId={}, userId={}", chatRoomId, principal.getUserId());

        try {
            User user = userService.getMemberById(principal.getUserId());

            messageCommandService.publishImmediately(
                    chatRoomId, user.getUserId(), user.getNickname(),
                    user.getProfileImage(), request.text());

            asyncMessageService.saveMessageAsync(chatRoomId, principal.getUserId(), request.text());

            log.info("[WebSocket.Publish] chatRoomId={}, userId={}", chatRoomId, principal.getUserId());

        } catch (CustomException e) {
            log.error("[WebSocket.Error] {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[WebSocket.Error] unexpected", e);
            throw new CustomException(ErrorCode.MESSAGE_SERVER_ERROR);
        }
    }

    @MessageExceptionHandler(CustomException.class)
    @SendToUser("/sub/errors")
    public String handleCustomException(CustomException ex) {
        return ex.getErrorCode().getMessage();
    }

    private UserPrincipal extractPrincipal(SimpMessageHeaderAccessor accessor) {
        return (UserPrincipal)
                ((UsernamePasswordAuthenticationToken) accessor.getUser()).getPrincipal();
    }
}
