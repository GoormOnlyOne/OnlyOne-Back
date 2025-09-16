package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.service.ChatRoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.onlyone.global.config.SecurityConfig;
import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.example.onlyone.OnlyoneApplication;

@WebMvcTest(
        controllers = ChatRoomRestController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        SecurityConfig.class,
                        JwtAuthenticationFilter.class,
                        OnlyoneApplication.class
                })
        }
)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 꺼버림
class ChatRoomRestControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ChatRoomService chatRoomService;
    @MockitoBean
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("사용자가_모임에서_참여_중인_채팅방_목록을_반환한다")
    void getUserChatRoomsSuccess() throws Exception {
        Long clubId = 1L;
        List<ChatRoomResponse> stub = List.of(
                ChatRoomResponse.builder()
                        .chatRoomId(101L)
                        .clubId(clubId)
                        .scheduleId(null)
                        .type(Type.valueOf("CLUB"))
                        .chatRoomName("모임 채팅방")
                        .lastMessageText("안녕")
                        .lastMessageTime(LocalDateTime.parse("2025-08-25T10:00:00"))
                        .build()
        );
        given(chatRoomService.getChatRoomsUserJoinedInClub(clubId)).willReturn(stub);

        mockMvc.perform(get("/clubs/{clubId}/chat", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].chatRoomId").value(101L))
                .andExpect(jsonPath("$.data[0].chatRoomName").value("모임 채팅방"));
    }
}