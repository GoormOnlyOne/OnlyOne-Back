package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.port.FeedStoragePort;
import com.example.onlyone.domain.feed.port.FeedStoragePort.FeedDetailItem;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import com.example.onlyone.domain.feed.service.FeedCacheService.DetailCacheEntry;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.feed.exception.FeedErrorCode;
import com.example.onlyone.global.exception.CustomException;
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
    private final FeedStoragePort feedStoragePort;
    private final UserService userService;
    private final UserClubRepository userClubRepository;
    private final FeedCacheService cache;
    private final FeedRenderService renderService;

    private static final int MAX_CACHEABLE_PAGE = 5;
    private static final int DEFAULT_COMMENT_PAGE_SIZE = 20;
    // pf:{userId}:{page}:{size} — 개인 피드 캐시 키
    private static final String PERSONAL_FEED_KEY_PREFIX = "pf:";
    // ppf:{userId}:{page}:{size} — 인기 피드 캐시 키
    private static final String POPULAR_FEED_KEY_PREFIX = "ppf:";

    // ── 모임 피드 ──

    public Page<FeedSummaryResponseDto> getFeedList(Long clubId, Pageable pageable) {
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_FOUND);
        }
        return feedStoragePort.findClubFeedSummaries(clubId, pageable);
    }

    // ── 피드 상세 ──

    public FeedDetailResponseDto getFeedDetail(Long clubId, Long feedId) {
        Long currentUserId = userService.getCurrentUserId();

        DetailCacheEntry cached = cache.getDetail(feedId);
        if (cached != null) {
            return buildDetailFromCache(cached, feedId, currentUserId);
        }

        FeedDetailItem detail = feedStoragePort.findFeedDetailWithRelations(feedId, clubId)
                .orElseThrow(() -> new CustomException(FeedErrorCode.FEED_NOT_FOUND));

        List<String> imageUrls = detail.imageUrls();
        boolean isLiked = feedStoragePort.isLikedByUser(feedId, currentUserId);
        boolean isMine = detail.userId().equals(currentUserId);

        Pageable commentPage = PageRequest.of(0, DEFAULT_COMMENT_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"));
        List<FeedCommentResponseDto> comments = feedStoragePort.findCommentsByFeedId(feedId, commentPage).stream()
                .map(c -> c.toDto(currentUserId)).toList();
        long repostCount = feedStoragePort.countRepostsByParentId(feedId);

        cache.putDetail(feedId, detail, imageUrls, comments, repostCount);
        return FeedDetailResponseDto.from(detail, imageUrls, isLiked, isMine, comments, repostCount);
    }

    // ── 전체 피드 ──

    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        return loadFeed(pageable, PERSONAL_FEED_KEY_PREFIX, true);
    }

    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        return loadFeed(pageable, POPULAR_FEED_KEY_PREFIX, false);
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
            pass1 = chronological
                    ? feedStoragePort.findPersonalFeedIds(clubIds, pageable)
                    : feedStoragePort.findPopularFeedIds(clubIds, pageable);
            if (cacheable) cache.putPass1(pass1Key, pass1);
        }

        List<FeedOverviewDto> result = renderService.buildOverviewList(pass1, userId);
        cache.putResult(resultKey, result);
        return result;
    }

    private FeedDetailResponseDto buildDetailFromCache(DetailCacheEntry cached, Long feedId, Long currentUserId) {
        boolean isLiked = feedStoragePort.isLikedByUser(feedId, currentUserId);
        boolean isMine = cached.detail().userId().equals(currentUserId);
        List<FeedCommentResponseDto> comments = cached.comments().stream()
                .map(c -> new FeedCommentResponseDto(c.commentId(), c.userId(), c.nickname(), c.profileImage(),
                        c.content(), c.createdAt(), c.userId().equals(currentUserId)))
                .toList();
        return FeedDetailResponseDto.from(cached.detail(), cached.imageUrls(), isLiked, isMine, comments, cached.repostCount());
    }
}
