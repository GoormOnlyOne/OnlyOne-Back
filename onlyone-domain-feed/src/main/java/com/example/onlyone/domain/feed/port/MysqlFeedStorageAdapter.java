package com.example.onlyone.domain.feed.port;

import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.feed.storage", havingValue = "mysql", matchIfMissing = true)
public class MysqlFeedStorageAdapter implements FeedStoragePort {

    private static final int CLUB_CHUNK_SIZE = 5;

    private final FeedRepository feedRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final FeedLikeRepository feedLikeRepository;

    @Override
    public List<FeedIdWithCounts> findPersonalFeedIds(List<Long> clubIds, Pageable pageable) {
        return feedRepository.findFeedIdsByClubIdsUnionAll(clubIds, null, pageable.getPageSize());
    }

    @Override
    public List<FeedIdWithCounts> findPersonalFeedIdsCursor(List<Long> clubIds, Long cursor, int limit) {
        return feedRepository.findFeedIdsByClubIdsUnionAll(clubIds, cursor, limit);
    }

    @Override
    public List<FeedIdWithCounts> findPopularFeedIds(List<Long> clubIds, Pageable pageable) {
        return feedRepository.findPopularFeedIdsByScoreUnionAll(clubIds, pageable.getPageSize());
    }

    @Override
    public Page<FeedSummaryResponseDto> findClubFeedSummaries(Long clubId, Pageable pageable) {
        return feedRepository.findFeedSummaries(clubId, pageable);
    }

    @Override
    public Optional<FeedDetailItem> findFeedDetailWithRelations(Long feedId, Long clubId) {
        return feedRepository.findByIdAndClubIdWithRelations(feedId, clubId)
                .map(this::toDetailItem);
    }

    @Override
    public List<FeedDetailItem> findFeedsByIdsWithRelations(List<Long> feedIds) {
        if (feedIds.isEmpty()) return List.of();
        return feedRepository.findByIdsWithRelations(feedIds).stream()
                .map(this::toDetailItem).toList();
    }

    @Override
    public Map<Long, Long> countDirectRepostsInBatch(List<Long> feedIds) {
        if (feedIds.isEmpty()) return Map.of();
        return feedRepository.countDirectRepostsIn(feedIds).stream()
                .collect(Collectors.toMap(
                        FeedRepositoryCustom.ParentRepostCount::parentId,
                        FeedRepositoryCustom.ParentRepostCount::cnt));
    }

    @Override
    public long countRepostsByParentId(Long feedId) {
        return feedRepository.countByParentFeedId(feedId);
    }

    @Override
    public List<CommentItem> findCommentsByFeedId(Long feedId, Pageable pageable) {
        return feedCommentRepository.findByFeedIdWithUser(feedId, pageable).stream()
                .map(this::toCommentItem).toList();
    }

    @Override
    public boolean isLikedByUser(Long feedId, Long userId) {
        return feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedId, userId);
    }

    @Override
    public Set<Long> findLikedFeedIdsByUser(List<Long> feedIds, Long userId) {
        if (feedIds.isEmpty()) return Set.of();
        return feedLikeRepository.findLikedFeedIdsByUser(feedIds, userId);
    }

    // ── 매핑 ──

    private FeedDetailItem toDetailItem(Feed f) {
        return new FeedDetailItem(
                f.getFeedId(), f.getContent(),
                f.getClub() != null ? f.getClub().getClubId() : null,
                f.getClub() != null ? f.getClub().getName() : null,
                f.getUser() != null ? f.getUser().getUserId() : null,
                f.getUser() != null ? f.getUser().getNickname() : null,
                f.getUser() != null ? f.getUser().getProfileImage() : null,
                f.getParentFeedId(), f.getRootFeedId(),
                f.getLikeCount(), f.getCommentCount(),
                f.getFeedImages() != null
                        ? f.getFeedImages().stream().map(FeedImage::getFeedImage).filter(Objects::nonNull).toList()
                        : List.of(),
                f.getCreatedAt(), f.getModifiedAt()
        );
    }

    private CommentItem toCommentItem(FeedComment c) {
        return new CommentItem(
                c.getFeedCommentId(),
                c.getUser().getUserId(),
                c.getUser().getNickname(),
                c.getUser().getProfileImage(),
                c.getContent(),
                c.getCreatedAt()
        );
    }
}
