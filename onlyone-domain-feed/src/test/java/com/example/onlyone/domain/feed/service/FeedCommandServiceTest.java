package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.entity.*;
import com.example.onlyone.domain.feed.repository.FeedRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedCommandService 단위 테스트")
class FeedCommandServiceTest {

    @InjectMocks private FeedCommandService feedCommandService;
    @Mock private ClubRepository clubRepository;
    @Mock private FeedRepository feedRepository;
    @Mock private UserService userService;
    @Mock private UserClubRepository userClubRepository;

    private User user;
    private User otherUser;
    private Club club;
    private Feed feed;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .userId(1L).kakaoId(11111L).nickname("테스트유저")
                .status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1995, 1, 1))
                .city("서울").district("강남구").profileImage("profile.jpg")
                .build();

        otherUser = User.builder()
                .userId(2L).kakaoId(22222L).nickname("다른유저")
                .status(Status.ACTIVE).gender(Gender.FEMALE)
                .birth(LocalDate.of(1998, 5, 15))
                .city("서울").district("서초구").profileImage("other.jpg")
                .build();

        club = Club.builder()
                .clubId(100L).name("테스트 모임").userLimit(20)
                .description("테스트 모임 설명").clubImage("club.jpg")
                .city("서울").district("강남구")
                .build();

        feed = Feed.builder()
                .feedId(10L).content("테스트 피드 내용")
                .club(club).user(user)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImageId(1L).feedImage("img1.jpg").feed(feed).build());
    }

    // =========================================================================
    @Nested
    @DisplayName("피드 생성")
    class CreateFeed {

        @Test
        @DisplayName("성공: 피드와 이미지가 저장된다")
        void success() {
            FeedRequestDto requestDto = new FeedRequestDto(List.of("url1.jpg", "url2.jpg"), "새 피드 내용");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.findByUserAndClub(user, club))
                    .thenReturn(Optional.of(UserClub.builder()
                            .userClubId(1L).user(user).club(club).clubRole(ClubRole.MEMBER).build()));
            when(feedRepository.save(any(Feed.class))).thenAnswer(invocation -> invocation.getArgument(0));

            feedCommandService.createFeed(club.getClubId(), requestDto);

            verify(feedRepository).save(argThat(savedFeed -> {
                assertThat(savedFeed.getContent()).isEqualTo("새 피드 내용");
                assertThat(savedFeed.getClub()).isEqualTo(club);
                assertThat(savedFeed.getUser()).isEqualTo(user);
                assertThat(savedFeed.getFeedImages()).hasSize(2);
                assertThat(savedFeed.getFeedImages().get(0).getFeedImage()).isEqualTo("url1.jpg");
                assertThat(savedFeed.getFeedImages().get(1).getFeedImage()).isEqualTo("url2.jpg");
                return true;
            }));
        }

        @Test
        @DisplayName("실패: 모임이 없으면 CLUB_NOT_FOUND")
        void failClubNotFound() {
            FeedRequestDto requestDto = new FeedRequestDto(List.of("url1.jpg"), "내용");
            when(clubRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> feedCommandService.createFeed(999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            FeedRequestDto requestDto = new FeedRequestDto(List.of("url1.jpg"), "내용");
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.findByUserAndClub(user, club)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> feedCommandService.createFeed(club.getClubId(), requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_JOIN);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("피드 수정")
    class UpdateFeed {

        @Test
        @DisplayName("성공: 내용과 이미지가 수정된다")
        void success() {
            FeedRequestDto requestDto = new FeedRequestDto(List.of("new1.jpg", "new2.jpg"), "수정된 내용");
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));

            feedCommandService.updateFeed(club.getClubId(), feed.getFeedId(), requestDto);

            assertThat(feed.getContent()).isEqualTo("수정된 내용");
            assertThat(feed.getFeedImages()).hasSize(2);
            assertThat(feed.getFeedImages().get(0).getFeedImage()).isEqualTo("new1.jpg");
            assertThat(feed.getFeedImages().get(1).getFeedImage()).isEqualTo("new2.jpg");
        }

        @Test
        @DisplayName("실패: 피드 작성자가 아니면 UNAUTHORIZED_FEED_ACCESS")
        void failUnauthorized() {
            FeedRequestDto requestDto = new FeedRequestDto(List.of("new.jpg"), "수정 시도");
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(otherUser);
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));

            assertThatThrownBy(() -> feedCommandService.updateFeed(club.getClubId(), feed.getFeedId(), requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }

        @Test
        @DisplayName("실패: 피드가 없으면 FEED_NOT_FOUND")
        void failFeedNotFound() {
            FeedRequestDto requestDto = new FeedRequestDto(List.of("new.jpg"), "수정 시도");
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.findByFeedIdAndClub(999L, club)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> feedCommandService.updateFeed(club.getClubId(), 999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FEED_NOT_FOUND);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("피드 삭제")
    class SoftDeleteFeed {

        @Test
        @DisplayName("성공: 소프트 삭제가 실행된다")
        void success() {
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.clearParentAndRootForChildren(feed.getFeedId())).thenReturn(2);
            when(feedRepository.clearRootForDescendants(feed.getFeedId())).thenReturn(1);
            when(feedRepository.softDeleteById(feed.getFeedId())).thenReturn(1);

            feedCommandService.softDeleteFeed(club.getClubId(), feed.getFeedId());

            verify(feedRepository).clearParentAndRootForChildren(feed.getFeedId());
            verify(feedRepository).clearRootForDescendants(feed.getFeedId());
            verify(feedRepository).softDeleteById(feed.getFeedId());
        }

        @Test
        @DisplayName("실패: 작성자가 아니면 UNAUTHORIZED_FEED_ACCESS")
        void failUnauthorized() {
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(otherUser);

            assertThatThrownBy(() -> feedCommandService.softDeleteFeed(club.getClubId(), feed.getFeedId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED_FEED_ACCESS);

            verify(feedRepository, never()).softDeleteById(anyLong());
        }

        @Test
        @DisplayName("실패: softDeleteById가 0 반환하면 FEED_NOT_FOUND")
        void failAlreadyDeleted() {
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.clearParentAndRootForChildren(feed.getFeedId())).thenReturn(0);
            when(feedRepository.clearRootForDescendants(feed.getFeedId())).thenReturn(0);
            when(feedRepository.softDeleteById(feed.getFeedId())).thenReturn(0);

            assertThatThrownBy(() -> feedCommandService.softDeleteFeed(club.getClubId(), feed.getFeedId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FEED_NOT_FOUND);
        }
    }
}
