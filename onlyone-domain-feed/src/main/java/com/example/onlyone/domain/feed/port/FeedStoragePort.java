package com.example.onlyone.domain.feed.port;

import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 피드 조회 저장소 추상화 포트.
 * MySQL / MongoDB 어댑터가 구현한다.
 * 설정: app.feed.storage=mysql|mongodb
 */
public interface FeedStoragePort {

    // ── 개인 피드 (최신순) — 핵심 병목 ──

    List<FeedRepositoryCustom.FeedIdWithCounts> findPersonalFeedIds(List<Long> clubIds, Pageable pageable);

    // ── 인기 피드 (스코어순) ──

    List<FeedRepositoryCustom.FeedIdWithCounts> findPopularFeedIds(List<Long> clubIds, Pageable pageable);

    // ── 모임 피드 목록 ──

    Page<FeedSummaryResponseDto> findClubFeedSummaries(Long clubId, Pageable pageable);

    // ── 피드 상세 (Feed + User + Club + Images 한번에) ──

    Optional<FeedDetailItem> findFeedDetailWithRelations(Long feedId, Long clubId);

    // ── 배치 피드 로딩 (Pass2 렌더링) ──

    List<FeedDetailItem> findFeedsByIdsWithRelations(List<Long> feedIds);

    // ── 리포스트 카운트 ──

    Map<Long, Long> countDirectRepostsInBatch(List<Long> feedIds);

    long countRepostsByParentId(Long feedId);

    // ── 댓글 ──

    List<CommentItem> findCommentsByFeedId(Long feedId, Pageable pageable);

    // ── 좋아요 체크 ──

    boolean isLikedByUser(Long feedId, Long userId);

    Set<Long> findLikedFeedIdsByUser(List<Long> feedIds, Long userId);

    // ── DTO ──

    record FeedDetailItem(
            Long feedId, String content,
            Long clubId, String clubName,
            Long userId, String nickname, String profileImage,
            Long parentFeedId, Long rootFeedId,
            Long likeCount, Long commentCount,
            List<String> imageUrls,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime modifiedAt
    ) {}

    record CommentItem(
            Long commentId, Long userId, String nickname, String profileImage,
            String content, java.time.LocalDateTime createdAt
    ) {
        public FeedCommentResponseDto toDto(Long currentUserId) {
            return new FeedCommentResponseDto(commentId, userId, nickname, profileImage,
                    content, createdAt, userId.equals(currentUserId));
        }
    }
}
