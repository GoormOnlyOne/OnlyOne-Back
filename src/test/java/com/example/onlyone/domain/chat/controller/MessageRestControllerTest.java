package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.OnlyoneApplication;
import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.service.MessageService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;

import com.example.onlyone.global.config.SecurityConfig;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = MessageRestController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        SecurityConfig.class,
                        JwtAuthenticationFilter.class,
                        OnlyoneApplication.class
                })
        }
)
@AutoConfigureMockMvc(addFilters = false)
class MessageRestControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean MessageService messageService;
    @MockitoBean UserService userService;
    @MockitoBean
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private ChatMessageResponse msg(Long id, Long roomId, Long kakaoId, String nick, String text, String imageUrl, LocalDateTime at, boolean deleted) {
        return ChatMessageResponse.builder()
                .messageId(id)
                .chatRoomId(roomId)
                .senderId(kakaoId)
                .senderNickname(nick)
                .profileImage(null)
                .text(text)
                .imageUrl(imageUrl)
                .sentAt(at)
                .deleted(deleted)
                .build();
    }

    @Test
    @DisplayName("성공적으로_메시지를_전송한다")
    void sendMessageSuccess() throws Exception {
        Long roomId = 101L;
        Long kakaoId = 1001L;

        var saved = msg(555L, roomId, kakaoId, "보낸이", "안녕", null, LocalDateTime.now(), false);
        given(messageService.saveMessage(eq(roomId), eq(kakaoId), eq("안녕")))
                .willReturn(saved);

        var req = ChatMessageRequest.fromText(kakaoId, "안녕");

        mockMvc.perform(post("/chat/{chatRoomId}/messages", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messageId").value(555))
                .andExpect(jsonPath("$.data.chatRoomId").value(101))
                .andExpect(jsonPath("$.data.senderId").value(1001))
                .andExpect(jsonPath("$.data.text").value("안녕"));
    }

    @Test
    @DisplayName("성공적으로_본인이_보낸_메시지를_삭제한다")
    void deleteMessageSuccess() throws Exception {
        Long messageId = 1L;
        var me = User.builder().userId(1L).kakaoId(1001L).status(Status.ACTIVE).build();

        given(userService.getCurrentUser()).willReturn(me);

        mockMvc.perform(delete("/chat/messages/{messageId}", messageId))
                .andExpect(status().isNoContent());

        then(messageService).should().deleteMessage(eq(messageId), eq(1L));
    }

    @Test
    @DisplayName("커서_기반으로_메세지_목록을_조회한다")
    void getChatRoomMessagesSuccess() throws Exception {
        Long roomId = 101L;

        var now = LocalDateTime.now().withNano(0);
        var m1 = msg(10L, roomId, 2001L, "A", "hi", null, now.minusSeconds(2), false);
        var m2 = msg(11L, roomId, 2002L, "B", null, "https://cdn/img.png", now.minusSeconds(1), false);

        var page = ChatRoomMessageResponse.builder()
                .chatRoomId(roomId)
                .chatRoomName("모임A")
                .messages(List.of(m1, m2))
                .hasMore(true)
                .nextCursorId(10L)
                .nextCursorAt(m1.getSentAt())
                .build();

        given(messageService.getChatRoomMessages(eq(roomId), eq(50), isNull(), isNull()))
                .willReturn(page);

        mockMvc.perform(get("/chat/{chatRoomId}/messages", roomId)
                        .param("size", "50")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.chatRoomId").value(101))
                .andExpect(jsonPath("$.data.chatRoomName").value("모임A"))
                .andExpect(jsonPath("$.data.messages[0].messageId").value(10))
                .andExpect(jsonPath("$.data.messages[1].messageId").value(11))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursorId").value(10));
    }

    @Test
    @DisplayName("메세지_저장_중_오류_발생시_에러를_반환한다")
    void sendMessageFailureReturnsServerError() throws Exception {
        Long roomId = 101L;
        Long kakaoId = 1001L;

        given(messageService.saveMessage(eq(roomId), eq(kakaoId), eq("안녕")))
                .willThrow(new CustomException(ErrorCode.MESSAGE_SERVER_ERROR));

        var req = ChatMessageRequest.fromText(kakaoId, "안녕");

        mockMvc.perform(post("/chat/{chatRoomId}/messages", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("MESSAGE_SERVER_ERROR"))
                .andExpect(jsonPath("$.data.message").value("메시지 조회 중 오류가 발생했습니다."));
    }

    @Test
    @DisplayName("커서로_다음_페이지를_이어서_조회한다")
    void getChatRoomMessagesPagedFollowUp() throws Exception {
        Long roomId = 101L;
        var now = LocalDateTime.now().withNano(0);

        var older1 = msg(8L, roomId, 2001L, "A", "m8", null, now.minusSeconds(4), false);
        var older2 = msg(9L, roomId, 2002L, "B", "m9", null, now.minusSeconds(3), false);

        var page2 = ChatRoomMessageResponse.builder()
                .chatRoomId(roomId)
                .chatRoomName("모임A")
                .messages(List.of(older1, older2)) // ASC
                .hasMore(false)
                .nextCursorId(8L)
                .nextCursorAt(older1.getSentAt())
                .build();

        given(messageService.getChatRoomMessages(eq(roomId), eq(50), eq(10L), eq(now.minusSeconds(2))))
                .willReturn(page2);

        mockMvc.perform(get("/chat/{chatRoomId}/messages", roomId)
                        .param("size", "50")
                        .param("cursorId", "10")
                        .param("cursorAt", now.minusSeconds(2).toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages[0].messageId").value(8))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }
}
