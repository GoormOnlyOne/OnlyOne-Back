package com.example.onlyone.global.reactive;

import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.MessageCommandService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.chat.websocket", havingValue = "reactive")
@RequiredArgsConstructor
public class ReactiveChatWebSocketHandler extends TextWebSocketHandler {

    private final ReactiveChatConnectionManager connectionManager;
    private final MessageCommandService messageCommandService;
    private final AsyncMessageService asyncMessageService;
    private final UserService userService;
    private final UserChatRoomRepository userChatRoomRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        connectionManager.registerSession(session, userId);
        log.info("Reactive WebSocket 연결: sessionId={}, userId={}", session.getId(), userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String action = node.has("action") ? node.get("action").asText() : null;

            if (action == null) {
                sendError(session, "CHAT_4001", "action 필드가 필요합니다.");
                return;
            }

            switch (action) {
                case "subscribe" -> handleSubscribe(session, node);
                case "unsubscribe" -> handleUnsubscribe(session, node);
                case "send" -> handleSend(session, node);
                default -> sendError(session, "CHAT_4001", "알 수 없는 action: " + action);
            }
        } catch (JsonProcessingException e) {
            sendError(session, "CHAT_4001", "잘못된 JSON 형식입니다.");
        } catch (Exception e) {
            log.error("Reactive WS 메시지 처리 실패: sessionId={}", session.getId(), e);
            sendError(session, "CHAT_5001", "서버 오류가 발생했습니다.");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connectionManager.removeSession(session);
        log.info("Reactive WebSocket 종료: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Reactive WS 전송 오류: sessionId={}", session.getId(), exception);
        connectionManager.removeSession(session);
    }

    private void handleSubscribe(WebSocketSession session, JsonNode node) {
        Long chatRoomId = extractChatRoomId(node);
        if (chatRoomId == null) {
            sendError(session, "CHAT_4001", "chatRoomId가 필요합니다.");
            return;
        }

        Long userId = (Long) session.getAttributes().get("userId");
        if (!userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId)) {
            sendError(session, "CHAT_4031", "해당 채팅방 접근이 거부되었습니다.");
            return;
        }

        connectionManager.subscribeRoom(session, chatRoomId);
        sendJson(session, Map.of("type", "subscribed", "chatRoomId", chatRoomId));
    }

    private void handleUnsubscribe(WebSocketSession session, JsonNode node) {
        Long chatRoomId = extractChatRoomId(node);
        if (chatRoomId == null) {
            sendError(session, "CHAT_4001", "chatRoomId가 필요합니다.");
            return;
        }

        connectionManager.unsubscribeRoom(session, chatRoomId);
        sendJson(session, Map.of("type", "unsubscribed", "chatRoomId", chatRoomId));
    }

    private void handleSend(WebSocketSession session, JsonNode node) {
        Long chatRoomId = extractChatRoomId(node);
        if (chatRoomId == null) {
            sendError(session, "CHAT_4001", "chatRoomId가 필요합니다.");
            return;
        }

        Long userId = (Long) session.getAttributes().get("userId");
        if (!userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId)) {
            sendError(session, "CHAT_4031", "해당 채팅방 접근이 거부되었습니다.");
            return;
        }

        String text = node.has("text") ? node.get("text").asText(null) : null;
        String imageUrl = node.has("imageUrl") && !node.get("imageUrl").isNull()
                ? node.get("imageUrl").asText(null) : null;

        String rawText = text;
        if (imageUrl != null && !imageUrl.isBlank()) {
            rawText = "IMAGE::" + imageUrl;
        }

        if (rawText == null || rawText.isBlank()) {
            sendError(session, "CHAT_4001", "text 또는 imageUrl이 필요합니다.");
            return;
        }

        User user = userService.getMemberById(userId);

        messageCommandService.publishImmediately(
                chatRoomId, user.getUserId(), user.getNickname(),
                user.getProfileImage(), rawText);

        asyncMessageService.saveMessageAsync(chatRoomId, user.getUserId(), rawText);
    }

    private Long extractChatRoomId(JsonNode node) {
        if (node.has("chatRoomId") && node.get("chatRoomId").isNumber()) {
            return node.get("chatRoomId").asLong();
        }
        return null;
    }

    private void sendError(WebSocketSession session, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("code", code);
        error.put("message", message);
        sendJson(session, error);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) return;
        try {
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.warn("JSON 응답 전송 실패: sessionId={}", session.getId(), e);
        }
    }
}
