package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import com.example.onlyone.domain.feed.service.FeedCacheService.DetailCacheEntry;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FeedQueryService {

    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final FeedLikeRepository feedLikeRepository;
    private final UserService userService;
    private final UserClubRepository userClubRepository;
    private final FeedCacheService cache;
    private final FeedRenderService renderService;

    private static final int CLUB_CHUNK_SIZE = 5;
    private static final int MAX_CACHEABLE_PAGE = 5;
    private static final int DEFAULT_COMMENT_PAGE_SIZE = 20;

    // ── 모임 피드 ──

    public Page<FeedSummaryResponseDto> getFeedList(Long clubId, Pageable pageable) {
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }
        return feedRepository.findFeedSummaries(clubId, pageable);
    }

    // ── 피드 상세 ──

    public FeedDetailResponseDto getFeedDetail(Long clubId, Long feedId) {
        Long currentUserId = userService.getCurrentUserId();

        DetailCacheEntry cached = cache.getDetail(feedId);
        if (cached != null) {
            return buildDetailFromCache(cached, feedId, currentUserId);
        }

        Feed feed = feedRepository.findByIdAndClubIdWithRelations(feedId, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        List<String> imageUrls = feed.getFeedImages().stream()
                .map(FeedImage::getFeedImage).toList();
        boolean isLiked = feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedId, currentUserId);
        boolean isMine = feed.getUser().getUserId().equals(currentUserId);

        Pageable commentPage = PageRequest.of(0, DEFAULT_COMMENT_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"));
        List<FeedCommentResponseDto> comments = feedCommentRepository.findByFeedIdWithUser(feedId, commentPage).stream()
                .map(c -> FeedCommentResponseDto.from(c, currentUserId)).toList();
        long repostCount = feedRepository.countByParentFeedId(feedId);

        cache.putDetail(feedId, feed, imageUrls, comments, repostCount);
        return FeedDetailResponseDto.from(feed, imageUrls, isLiked, isMine, comments, repostCount);
    }

    // ── 전체 피드 ──

    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        return loadFeed(pageable, "pf:", true);
    }

    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        return loadFeed(pageable, "ppf:", false);
    }

    // ── private ──

    private List<FeedOverviewDto> loadFeed(Pageable pageable, String prefix, boolean chronological) {
        Long userId = userService.getCurrentUserId();
        boolean cacheable = pageable.getPageNumber() <= MAX_CACHEABLE_PAGE;

        String resultKey = cacheable ? prefix + userId + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize() : null;
        List<FeedOverviewDto> cachedResult = cache.getResult(resultKey);
        if (cachedResult != null) return cachedResult;

        List<Long> clubIds = userClubRepository.findAccessibleClubIds(userId);
        if (clubIds.isEmpty()) return Collections.emptyList();

        String pass1Key = cacheable ? prefix + userId + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize() : null;
        List<FeedIdWithCounts> pass1 = cacheable ? cache.getPass1(pass1Key) : null;

        if (pass1 == null) {
            pass1 = chronological ? loadChronological(clubIds, pageable) : feedRepository.findPopularFeedIdsByClubIds(clubIds, pageable);
            if (cacheable) cache.putPass1(pass1Key, pass1);
        }

        List<FeedOverviewDto> result = renderService.buildOverviewList(pass1, userId);
        cache.putResult(resultKey, result);
        return result;
    }

    private List<FeedIdWithCounts> loadChronological(List<Long> clubIds, Pageable pageable) {
        return (clubIds.size() <= CLUB_CHUNK_SIZE)
                ? feedRepository.findFeedIdsByClubIds(clubIds, pageable)
                : feedRepository.findFeedIdsByClubIdsChunked(clubIds, pageable, CLUB_CHUNK_SIZE);
    }

    private FeedDetailResponseDto buildDetailFromCache(DetailCacheEntry cached, Long feedId, Long currentUserId) {
        boolean isLiked = feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedId, currentUserId);
        boolean isMine = cached.feed().getUser().getUserId().equals(currentUserId);
        List<FeedCommentResponseDto> comments = cached.comments().stream()
                .map(c -> new FeedCommentResponseDto(c.commentId(), c.userId(), c.nickname(), c.profileImage(),
                        c.content(), c.createdAt(), c.userId().equals(currentUserId)))
                .toList();
        return FeedDetailResponseDto.from(cached.feed(), cached.imageUrls(), isLiked, isMine, comments, cached.repostCount());
    }
}
