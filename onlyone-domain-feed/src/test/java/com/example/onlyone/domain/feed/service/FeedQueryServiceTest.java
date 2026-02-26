package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.entity.*;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedQueryService 단위 테스트")
class FeedQueryServiceTest {

    @InjectMocks private FeedQueryService feedQueryService;
    @Mock private ClubRepository clubRepository;
    @Mock private FeedRepository feedRepository;
    @Mock private FeedCommentRepository feedCommentRepository;
    @Mock private FeedLikeRepository feedLikeRepository;
    @Mock private UserService userService;
    @Mock private UserClubRepository userClubRepository;
    @Mock private FeedCacheService cache;
    @Mock private FeedRenderService renderService;

    private User user;
    private User otherUser;
    private Club club;

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
    }

    @Nested
    @DisplayName("피드 상세 조회")
    class GetFeedDetail {

        @Test
        @DisplayName("성공: 피드 상세 정보가 반환된다")
        void success() {
            Feed feedWithCounts = Feed.builder()
                    .feedId(10L).content("테스트 피드 내용")
                    .club(club).user(user)
                    .likeCount(1L).commentCount(1L)
                    .build();
            feedWithCounts.getFeedImages().add(
                    FeedImage.builder().feedImageId(1L).feedImage("img1.jpg").feed(feedWithCounts).build());

            FeedComment comment = FeedComment.builder()
                    .feedCommentId(1L).content("댓글 내용")
                    .feed(feedWithCounts).user(otherUser)
                    .build();
            feedWithCounts.getFeedComments().add(comment);

            when(feedRepository.findByIdAndClubIdWithRelations(feedWithCounts.getFeedId(), club.getClubId()))
                    .thenReturn(Optional.of(feedWithCounts));
            when(userService.getCurrentUserId()).thenReturn(user.getUserId());
            when(feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedWithCounts.getFeedId(), user.getUserId()))
                    .thenReturn(true);
            when(feedCommentRepository.findByFeedIdWithUser(eq(feedWithCounts.getFeedId()), any(Pageable.class)))
                    .thenReturn(List.of(comment));
            when(feedRepository.countByParentFeedId(feedWithCounts.getFeedId())).thenReturn(3L);

            FeedDetailResponseDto result = feedQueryService.getFeedDetail(club.getClubId(), feedWithCounts.getFeedId());

            assertThat(result).isNotNull();
            assertThat(result.feedId()).isEqualTo(feedWithCounts.getFeedId());
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
}
