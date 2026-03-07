package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.port.FeedStoragePort;
import com.example.onlyone.domain.feed.port.FeedStoragePort.CommentItem;
import com.example.onlyone.domain.feed.port.FeedStoragePort.FeedDetailItem;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
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
    @Mock private FeedStoragePort feedStoragePort;
    @Mock private UserService userService;
    @Mock private UserClubRepository userClubRepository;
    @Mock private FeedCacheService cache;
    @Mock private FeedRenderService renderService;

    @Nested
    @DisplayName("피드 상세 조회")
    class GetFeedDetail {

        @Test
        @DisplayName("성공: 피드 상세 정보가 반환된다")
        void success() {
            Long feedId = 10L;
            Long clubId = 100L;
            Long userId = 1L;
            LocalDateTime now = LocalDateTime.now();

            FeedDetailItem detail = new FeedDetailItem(
                    feedId, "테스트 피드 내용",
                    clubId, "테스트 모임",
                    userId, "테스트유저", "profile.jpg",
                    null, null,
                    1L, 1L,
                    List.of("img1.jpg"),
                    now, now
            );

            CommentItem comment = new CommentItem(
                    1L, 2L, "다른유저", "other.jpg", "댓글 내용", now
            );

            when(feedStoragePort.findFeedDetailWithRelations(feedId, clubId))
                    .thenReturn(Optional.of(detail));
            when(userService.getCurrentUserId()).thenReturn(userId);
            when(feedStoragePort.isLikedByUser(feedId, userId)).thenReturn(true);
            when(feedStoragePort.findCommentsByFeedId(eq(feedId), any(Pageable.class)))
                    .thenReturn(List.of(comment));
            when(feedStoragePort.countRepostsByParentId(feedId)).thenReturn(3L);

            FeedDetailResponseDto result = feedQueryService.getFeedDetail(clubId, feedId);

            assertThat(result).isNotNull();
            assertThat(result.feedId()).isEqualTo(feedId);
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
