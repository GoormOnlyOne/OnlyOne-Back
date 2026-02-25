package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedCommentService 단위 테스트")
class FeedCommentServiceTest {

    @InjectMocks private FeedCommentService feedCommentService;
    @Mock private FeedRepository feedRepository;
    @Mock private FeedCommentRepository feedCommentRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;
    @Mock private TransactionTemplate transactionTemplate;

    private User user;
    private User otherUser;
    private Club club;
    private Feed feed;
    private FeedComment comment;

    @BeforeEach
    void setUp() {
        // TransactionTemplate mock이 callback을 실제 실행하도록 설정
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        user = User.builder()
                .userId(1L).kakaoId(11111L).nickname("테스트유저")
                .status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1995, 1, 1))
                .build();

        otherUser = User.builder()
                .userId(2L).kakaoId(22222L).nickname("다른유저")
                .status(Status.ACTIVE).gender(Gender.FEMALE)
                .birth(LocalDate.of(1998, 5, 15))
                .build();

        club = Club.builder()
                .clubId(100L).name("테스트 모임").userLimit(20)
                .description("설명").clubImage("club.jpg")
                .city("서울").district("강남구")
                .build();

        feed = Feed.builder()
                .feedId(10L).content("피드 내용")
                .club(club).user(user)
                .build();

        comment = FeedComment.builder()
                .feedCommentId(1L).content("댓글 내용")
                .feed(feed).user(user)
                .build();
    }

    @Nested
    @DisplayName("댓글 생성")
    class CreateComment {

        @Test
        @DisplayName("성공: 댓글이 저장되고 댓글 수가 증가한다")
        void success() {
            // given
            FeedCommentRequestDto dto = new FeedCommentRequestDto("새 댓글");
            when(feedRepository.findById(feed.getFeedId())).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), club.getClubId()))
                    .thenReturn(true);

            // when
            feedCommentService.createComment(club.getClubId(), feed.getFeedId(), dto);

            // then
            verify(feedCommentRepository).save(any(FeedComment.class));
            verify(feedRepository).incrementCommentCount(feed.getFeedId());
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failNotMember() {
            // given
            FeedCommentRequestDto dto = new FeedCommentRequestDto("새 댓글");
            when(feedRepository.findById(feed.getFeedId())).thenReturn(Optional.of(feed));
            when(userService.getCurrentUser()).thenReturn(user);
            when(userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), club.getClubId()))
                    .thenReturn(false);

            // when & then
            assertThatThrownBy(() -> feedCommentService.createComment(club.getClubId(), feed.getFeedId(), dto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CLUB_NOT_JOIN);

            verify(feedCommentRepository, never()).save(any());
        }

        @Test
        @DisplayName("실패: 피드가 없으면 FEED_NOT_FOUND")
        void failFeedNotFound() {
            // given
            FeedCommentRequestDto dto = new FeedCommentRequestDto("새 댓글");
            when(feedRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> feedCommentService.createComment(club.getClubId(), 999L, dto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.FEED_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("댓글 삭제")
    class DeleteComment {

        @Test
        @DisplayName("성공: 댓글 작성자가 삭제하면 댓글 수가 감소한다")
        void successByCommentAuthor() {
            // given
            when(feedRepository.findById(feed.getFeedId())).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(comment.getFeedCommentId())).thenReturn(Optional.of(comment));
            when(userService.getCurrentUser()).thenReturn(user);

            // when
            feedCommentService.deleteComment(club.getClubId(), feed.getFeedId(), comment.getFeedCommentId());

            // then
            verify(feedCommentRepository).delete(comment);
            verify(feedRepository).decrementCommentCount(feed.getFeedId());
        }

        @Test
        @DisplayName("성공: 피드 작성자도 댓글을 삭제할 수 있다")
        void successByFeedAuthor() {
            // given
            FeedComment otherComment = FeedComment.builder()
                    .feedCommentId(2L).content("다른 사람 댓글")
                    .feed(feed).user(otherUser)
                    .build();

            when(feedRepository.findById(feed.getFeedId())).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(2L)).thenReturn(Optional.of(otherComment));
            when(userService.getCurrentUser()).thenReturn(user); // feed owner

            // when
            feedCommentService.deleteComment(club.getClubId(), feed.getFeedId(), 2L);

            // then
            verify(feedCommentRepository).delete(otherComment);
        }

        @Test
        @DisplayName("실패: 권한 없는 사용자이면 UNAUTHORIZED_COMMENT_ACCESS")
        void failUnauthorized() {
            // given
            when(feedRepository.findById(feed.getFeedId())).thenReturn(Optional.of(feed));
            when(feedCommentRepository.findById(comment.getFeedCommentId())).thenReturn(Optional.of(comment));
            when(userService.getCurrentUser()).thenReturn(otherUser); // neither comment author nor feed author

            // when & then
            assertThatThrownBy(() ->
                    feedCommentService.deleteComment(club.getClubId(), feed.getFeedId(), comment.getFeedCommentId()))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);

            verify(feedCommentRepository, never()).delete(any());
        }
    }
}
