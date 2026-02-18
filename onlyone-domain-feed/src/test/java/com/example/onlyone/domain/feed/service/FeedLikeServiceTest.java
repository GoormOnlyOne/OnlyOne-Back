package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedLikeService 단위 테스트")
class FeedLikeServiceTest {

    @InjectMocks private FeedLikeService feedLikeService;
    @Mock private ClubRepository clubRepository;
    @Mock private UserService userService;
    @Mock private DefaultRedisScript<List> likeToggleScript;
    @Mock private StringRedisTemplate redis;
    @Mock private Clock clock;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .userId(1L).kakaoId(11111L).nickname("테스트유저")
                .status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1995, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("좋아요 토글")
    class ToggleLike {

        @Test
        @DisplayName("성공: 좋아요 추가 (Lua 스크립트 반환값 1)")
        void successLiked() {
            // given
            when(clubRepository.existsById(100L)).thenReturn(true);
            when(userService.getCurrentUser()).thenReturn(user);
            when(clock.millis()).thenReturn(Instant.now().toEpochMilli());
            doReturn(List.of(1L, 1L, 1L)).when(redis)
                    .execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any());

            // when
            boolean result = feedLikeService.toggleLike(100L, 10L);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("성공: 좋아요 취소 (Lua 스크립트 반환값 0)")
        void successUnliked() {
            // given
            when(clubRepository.existsById(100L)).thenReturn(true);
            when(userService.getCurrentUser()).thenReturn(user);
            when(clock.millis()).thenReturn(Instant.now().toEpochMilli());
            doReturn(List.of(0L, 0L, 1L)).when(redis)
                    .execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any());

            // when
            boolean result = feedLikeService.toggleLike(100L, 10L);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("실패: 모임이 없으면 CLUB_NOT_FOUND")
        void failClubNotFound() {
            // given
            when(clubRepository.existsById(999L)).thenReturn(false);

            // when & then
            assertThatThrownBy(() -> feedLikeService.toggleLike(999L, 10L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: Lua 스크립트 반환값이 null이면 IllegalStateException")
        void failScriptReturnsNull() {
            // given
            when(clubRepository.existsById(100L)).thenReturn(true);
            when(userService.getCurrentUser()).thenReturn(user);
            when(clock.millis()).thenReturn(Instant.now().toEpochMilli());
            doReturn(null).when(redis)
                    .execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any());

            // when & then
            assertThatThrownBy(() -> feedLikeService.toggleLike(100L, 10L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
