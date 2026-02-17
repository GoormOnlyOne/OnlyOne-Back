package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.entity.*;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedService 단위 테스트")
class FeedServiceTest {

    @InjectMocks private FeedService feedService;
    @Mock private ClubRepository clubRepository;
    @Mock private FeedRepository feedRepository;
    @Mock private UserService userService;
    @Mock private FeedCommentRepository feedCommentRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private NotificationService notificationService;
    @Mock private FeedLikeRepository feedLikeRepository;
    @Mock private DefaultRedisScript<List> likeToggleScript;
    @Mock private StringRedisTemplate redis;
    @Mock private Clock clock;

    private User user;
    private User otherUser;
    private Club club;
    private Feed feed;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .userId(1L)
                .kakaoId(11111L)
                .nickname("테스트유저")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1995, 1, 1))
                .city("서울")
                .district("강남구")
                .profileImage("profile.jpg")
                .build();

        otherUser = User.builder()
                .userId(2L)
                .kakaoId(22222L)
                .nickname("다른유저")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1998, 5, 15))
                .city("서울")
                .district("서초구")
                .profileImage("other.jpg")
                .build();

        club = Club.builder()
                .clubId(100L)
                .name("테스트 모임")
                .userLimit(20)
                .description("테스트 모임 설명")
                .clubImage("club.jpg")
                .city("서울")
                .district("강남구")
                .build();

        feed = Feed.builder()
                .feedId(10L)
                .content("테스트 피드 내용")
                .club(club)
                .user(user)
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
            // given
            FeedRequestDto requestDto = new FeedRequestDto(List.of("url1.jpg", "url2.jpg"), "새 피드 내용");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.findByUserAndClub(user, club))
                    .thenReturn(Optional.of(UserClub.builder()
                            .userClubId(1L).user(user).club(club).clubRole(ClubRole.MEMBER).build()));
            when(feedRepository.save(any(Feed.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            feedService.createFeed(club.getClubId(), requestDto);

            // then
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
            // given
            FeedRequestDto requestDto = new FeedRequestDto(List.of("url1.jpg"), "내용");

            when(clubRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> feedService.createFeed(999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            // given
            FeedRequestDto requestDto = new FeedRequestDto(List.of("url1.jpg"), "내용");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.findByUserAndClub(user, club)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> feedService.createFeed(club.getClubId(), requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLUB_NOT_JOIN);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("피드 수정")
    class UpdateFeed {

        @Test
        @DisplayName("성공: 내용과 이미지가 수정된다")
        void success() {
            // given
            FeedRequestDto requestDto = new FeedRequestDto(List.of("new1.jpg", "new2.jpg"), "수정된 내용");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));

            // when
            feedService.updateFeed(club.getClubId(), feed.getFeedId(), requestDto);

            // then
            assertThat(feed.getContent()).isEqualTo("수정된 내용");
            assertThat(feed.getFeedImages()).hasSize(2);
            assertThat(feed.getFeedImages().get(0).getFeedImage()).isEqualTo("new1.jpg");
            assertThat(feed.getFeedImages().get(1).getFeedImage()).isEqualTo("new2.jpg");
        }

        @Test
        @DisplayName("실패: 피드 작성자가 아니면 UNAUTHORIZED_FEED_ACCESS")
        void failUnauthorized() {
            // given
            FeedRequestDto requestDto = new FeedRequestDto(List.of("new.jpg"), "수정 시도");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(otherUser);
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));

            // when & then
            assertThatThrownBy(() -> feedService.updateFeed(club.getClubId(), feed.getFeedId(), requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }

        @Test
        @DisplayName("실패: 피드가 없으면 FEED_NOT_FOUND")
        void failFeedNotFound() {
            // given
            FeedRequestDto requestDto = new FeedRequestDto(List.of("new.jpg"), "수정 시도");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.findByFeedIdAndClub(999L, club)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> feedService.updateFeed(club.getClubId(), 999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.FEED_NOT_FOUND);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("좋아요 토글")
    class ToggleLike {

        @Test
        @DisplayName("성공: Redis 스크립트가 실행되고 결과가 반환된다")
        void success() {
            // given
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(userService.getCurrentUser()).thenReturn(user);
            when(clock.millis()).thenReturn(System.currentTimeMillis());
            when(redis.execute(eq(likeToggleScript), anyList(), any(Object[].class)))
                    .thenReturn(List.of(1L, 1L, 5L));

            // when
            boolean result = feedService.toggleLike(club.getClubId(), feed.getFeedId());

            // then
            assertThat(result).isTrue();
            verify(redis).execute(eq(likeToggleScript), anyList(), any(Object[].class));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("피드 상세 조회")
    class GetFeedDetail {

        @Test
        @DisplayName("성공: 피드 상세 정보가 반환된다")
        void success() {
            // given
            FeedComment comment = FeedComment.builder()
                    .feedCommentId(1L)
                    .content("댓글 내용")
                    .feed(feed)
                    .user(otherUser)
                    .build();
            feed.getFeedComments().add(comment);

            FeedLike like = FeedLike.builder()
                    .feedLikeId(1L)
                    .feed(feed)
                    .user(user)
                    .build();
            feed.getFeedLikes().add(like);

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feed.getFeedId(), user.getUserId()))
                    .thenReturn(true);
            when(feedRepository.countByParentFeedId(feed.getFeedId())).thenReturn(3L);

            // when
            FeedDetailResponseDto result = feedService.getFeedDetail(club.getClubId(), feed.getFeedId());

            // then
            assertThat(result).isNotNull();
            assertThat(result.feedId()).isEqualTo(feed.getFeedId());
            assertThat(result.content()).isEqualTo("테스트 피드 내용");
            assertThat(result.imageUrls()).containsExactly("img1.jpg");
            assertThat(result.isLiked()).isTrue();
            assertThat(result.isFeedMine()).isTrue();
            assertThat(result.repostCount()).isEqualTo(3L);
            assertThat(result.comments()).hasSize(1);
            assertThat(result.likeCount()).isEqualTo(1);
            assertThat(result.commentCount()).isEqualTo(1);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("댓글 작성")
    class CreateComment {

        @Test
        @DisplayName("성공: 댓글이 저장된다")
        void success() {
            // given
            FeedCommentRequestDto requestDto = new FeedCommentRequestDto("새 댓글");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), club.getClubId()))
                    .thenReturn(true);
            when(feedCommentRepository.save(any(FeedComment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            feedService.createComment(club.getClubId(), feed.getFeedId(), requestDto);

            // then
            verify(feedCommentRepository).save(argThat(savedComment -> {
                assertThat(savedComment.getContent()).isEqualTo("새 댓글");
                assertThat(savedComment.getFeed()).isEqualTo(feed);
                assertThat(savedComment.getUser()).isEqualTo(user);
                return true;
            }));
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            // given
            FeedCommentRequestDto requestDto = new FeedCommentRequestDto("댓글 시도");

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(otherUser);
            when(userClubRepository.existsByUser_UserIdAndClub_ClubId(otherUser.getUserId(), club.getClubId()))
                    .thenReturn(false);

            // when & then
            assertThatThrownBy(() -> feedService.createComment(club.getClubId(), feed.getFeedId(), requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLUB_NOT_JOIN);

            verify(feedCommentRepository, never()).save(any());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("댓글 삭제")
    class DeleteComment {

        private FeedComment comment;

        @BeforeEach
        void setUpComment() {
            comment = FeedComment.builder()
                    .feedCommentId(50L)
                    .content("삭제 대상 댓글")
                    .feed(feed)
                    .user(otherUser)
                    .build();
        }

        @Test
        @DisplayName("성공: 댓글 작성자가 삭제한다")
        void successByCommentAuthor() {
            // given
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(comment.getFeedCommentId())).thenReturn(Optional.of(comment));
            when(userService.getCurrentUser()).thenReturn(otherUser);

            // when
            feedService.deleteComment(club.getClubId(), feed.getFeedId(), comment.getFeedCommentId());

            // then
            verify(feedCommentRepository).delete(comment);
        }

        @Test
        @DisplayName("성공: 피드 작성자가 타인 댓글을 삭제한다")
        void successByFeedAuthor() {
            // given
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(comment.getFeedCommentId())).thenReturn(Optional.of(comment));
            when(userService.getCurrentUser()).thenReturn(user); // feed 작성자

            // when
            feedService.deleteComment(club.getClubId(), feed.getFeedId(), comment.getFeedCommentId());

            // then
            verify(feedCommentRepository).delete(comment);
        }

        @Test
        @DisplayName("실패: 권한 없는 사용자면 UNAUTHORIZED_COMMENT_ACCESS")
        void failUnauthorized() {
            // given
            User thirdUser = User.builder()
                    .userId(3L)
                    .kakaoId(33333L)
                    .nickname("제3자")
                    .status(Status.ACTIVE)
                    .gender(Gender.MALE)
                    .birth(LocalDate.of(2000, 1, 1))
                    .city("부산")
                    .district("해운대구")
                    .build();

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(comment.getFeedCommentId())).thenReturn(Optional.of(comment));
            when(userService.getCurrentUser()).thenReturn(thirdUser);

            // when & then
            assertThatThrownBy(() -> feedService.deleteComment(club.getClubId(), feed.getFeedId(), comment.getFeedCommentId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);

            verify(feedCommentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("실패: 댓글이 해당 피드에 속하지 않으면 FEED_NOT_FOUND")
        void failCommentNotBelongToFeed() {
            // given
            Feed anotherFeed = Feed.builder()
                    .feedId(99L)
                    .content("다른 피드")
                    .club(club)
                    .user(user)
                    .build();

            FeedComment orphanComment = FeedComment.builder()
                    .feedCommentId(60L)
                    .content("다른 피드의 댓글")
                    .feed(anotherFeed)
                    .user(otherUser)
                    .build();

            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(orphanComment.getFeedCommentId())).thenReturn(Optional.of(orphanComment));

            // when & then
            assertThatThrownBy(() -> feedService.deleteComment(club.getClubId(), feed.getFeedId(), orphanComment.getFeedCommentId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.FEED_NOT_FOUND);

            verify(feedCommentRepository, never()).delete(any());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("피드 삭제")
    class SoftDeleteFeed {

        @Test
        @DisplayName("성공: 소프트 삭제가 실행된다")
        void success() {
            // given
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.clearParentAndRootForChildren(feed.getFeedId())).thenReturn(2);
            when(feedRepository.clearRootForDescendants(feed.getFeedId())).thenReturn(1);
            when(feedRepository.softDeleteById(feed.getFeedId())).thenReturn(1);

            // when
            feedService.softDeleteFeed(club.getClubId(), feed.getFeedId());

            // then
            verify(feedRepository).clearParentAndRootForChildren(feed.getFeedId());
            verify(feedRepository).clearRootForDescendants(feed.getFeedId());
            verify(feedRepository).softDeleteById(feed.getFeedId());
        }

        @Test
        @DisplayName("실패: 작성자가 아니면 UNAUTHORIZED_FEED_ACCESS")
        void failUnauthorized() {
            // given
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(otherUser);

            // when & then
            assertThatThrownBy(() -> feedService.softDeleteFeed(club.getClubId(), feed.getFeedId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.UNAUTHORIZED_FEED_ACCESS);

            verify(feedRepository, never()).softDeleteById(anyLong());
        }

        @Test
        @DisplayName("실패: softDeleteById가 0 반환하면 FEED_NOT_FOUND")
        void failAlreadyDeleted() {
            // given
            when(clubRepository.findById(club.getClubId())).thenReturn(Optional.of(club));
            when(feedRepository.findByFeedIdAndClub(feed.getFeedId(), club)).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(feedRepository.clearParentAndRootForChildren(feed.getFeedId())).thenReturn(0);
            when(feedRepository.clearRootForDescendants(feed.getFeedId())).thenReturn(0);
            when(feedRepository.softDeleteById(feed.getFeedId())).thenReturn(0);

            // when & then
            assertThatThrownBy(() -> feedService.softDeleteFeed(club.getClubId(), feed.getFeedId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.FEED_NOT_FOUND);
        }
    }
}
