package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class UserChatRoomServiceTest {

    @InjectMocks
    UserChatRoomService userChatRoomService;

    @Mock
    UserChatRoomRepository userChatRoomRepository;

    @Test
    @DisplayName("채팅방에_참여_중이면_true를_반환한다")
    void isUserInChatRoomTrue() {
        // given
        Long userId = 1L;
        Long chatRoomId = 101L;
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId))
                .willReturn(true);

        // when
        boolean result = userChatRoomService.isUserInChatRoom(userId, chatRoomId);

        // then
        assertThat(result).isTrue();
        then(userChatRoomRepository).should(times(1))
                .existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId);
        then(userChatRoomRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("채팅방에_참여_중이_아니면_false를_반환한다")
    void isUserInChatRoomFalse() {
        // given
        Long userId = 2L;
        Long chatRoomId = 202L;
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId))
                .willReturn(false);

        // when
        boolean result = userChatRoomService.isUserInChatRoom(userId, chatRoomId);

        // then
        assertThat(result).isFalse();
        then(userChatRoomRepository).should(times(1))
                .existsByUserUserIdAndChatRoomChatRoomId(userId, chatRoomId);
        then(userChatRoomRepository).shouldHaveNoMoreInteractions();
    }
}
