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
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
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

    private record FeedRenderContext(
            Long userId,
            Set<Long> likedFeedIds,
            Map<Long, Feed> parentMap,
            Map<Long, Feed> rootMap,
            Map<Long, Long> repostCntMap,
            Map<Long, Long> likeCountMap,
            Map<Long, Long> commentCountMap
    ) {}

    // ── 모임 피드 ──

    public Page<FeedSummaryResponseDto> getFeedList(Long clubId, Pageable pageable) {
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }
        return feedRepository.findFeedSummariesByClubId(clubId, pageable);
    }

    public FeedDetailResponseDto getFeedDetail(Long clubId, Long feedId) {
        // 1쿼리: Feed + User + Club + Images JOIN FETCH (N+1 제거)
        Feed feed = feedRepository.findByIdAndClubIdWithRelations(feedId, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        Long currentUserId = userService.getCurrentUser().getUserId();

        List<String> imageUrls = feed.getFeedImages().stream()
                .map(FeedImage::getFeedImage)
                .collect(Collectors.toList());

        boolean isLiked = feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedId, currentUserId);
        boolean isMine = feed.getUser().getUserId().equals(currentUserId);

        // 1쿼리: Comments + User JOIN FETCH (N+1 제거)
        List<FeedCommentResponseDto> commentResponseDtos = feedCommentRepository.findByFeedIdWithUser(feedId).stream()
                .map(comment -> FeedCommentResponseDto.from(comment, currentUserId))
                .collect(Collectors.toList());
        long repostCount = feedRepository.countByParentFeedId(feedId);

        return FeedDetailResponseDto.from(feed, imageUrls, isLiked, isMine, commentResponseDtos, repostCount);
    }

    // ── 전체 피드 (3-pass 최적화) ──

    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();
        List<Long> clubIds = resolveAccessibleClubIds(userId);
        if (clubIds.isEmpty()) return Collections.emptyList();

        List<FeedRepository.FeedIdWithCounts> pass1 =
                feedRepository.findFeedIdsWithCountsByClubIds(clubIds, pageable);
        return buildOverviewList(pass1, userId);
    }

    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();
        List<Long> clubIds = resolveAccessibleClubIds(userId);
        if (clubIds.isEmpty()) return Collections.emptyList();

        List<FeedRepository.FeedIdWithCounts> pass1 =
                feedRepository.findPopularFeedIdsWithCountsByClubIds(clubIds, pageable);
        return buildOverviewList(pass1, userId);
    }

    // ── private helpers ──

    private List<FeedOverviewDto> buildOverviewList(
            List<FeedRepository.FeedIdWithCounts> pass1, Long userId) {
        if (pass1.isEmpty()) return Collections.emptyList();

        List<Long> feedIds = pass1.stream()
                .map(FeedRepository.FeedIdWithCounts::getFeedId).toList();
        Map<Long, Long> likeCountMap = new HashMap<>();
        Map<Long, Long> commentCountMap = new HashMap<>();
        for (FeedRepository.FeedIdWithCounts row : pass1) {
            likeCountMap.put(row.getFeedId(), row.getLikeCount());
            commentCountMap.put(row.getFeedId(), row.getCommentCount());
        }

        List<Feed> feedsUnordered = feedRepository.findByIdsWithRelations(feedIds);
        Map<Long, Feed> feedMap = feedsUnordered.stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));
        List<Feed> feeds = feedIds.stream()
                .map(feedMap::get)
                .filter(Objects::nonNull)
                .toList();

        FeedRenderContext ctx = new FeedRenderContext(
                userId,
                feedLikeRepository.findLikedFeedIdsByUser(feedIds, userId),
                bulkLoadParents(feeds),
                bulkLoadRoots(feeds),
                countDirectReposts(feeds),
                likeCountMap,
                commentCountMap
        );

        return feeds.stream()
                .map(f -> toOverviewDto(f, ctx))
                .toList();
    }

    private List<Long> resolveAccessibleClubIds(Long userId) {
        return userClubRepository.findAccessibleClubIds(userId);
    }

    private Map<Long, Long> countDirectReposts(List<Feed> feeds) {
        Set<Long> targetIds = new HashSet<>();
        for (Feed f : feeds) {
            targetIds.add(f.getFeedId());
            if (f.getRootFeedId() != null) {
                targetIds.add(f.getRootFeedId());
            }
        }
        if (targetIds.isEmpty()) return Collections.emptyMap();
        return feedRepository.countDirectRepostsIn(new ArrayList<>(targetIds)).stream()
                .collect(Collectors.toMap(
                        FeedRepository.ParentRepostCount::getParentId,
                        FeedRepository.ParentRepostCount::getCnt));
    }

    private Map<Long, Feed> bulkLoadParents(List<Feed> feeds) {
        Set<Long> parentIds = feeds.stream()
                .map(Feed::getParentFeedId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) return Collections.emptyMap();

        return feedRepository.findByIdsWithRelations(new ArrayList<>(parentIds)).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));
    }

    private Map<Long, Feed> bulkLoadRoots(List<Feed> feeds) {
        Set<Long> rootIds = feeds.stream()
                .map(Feed::getRootFeedId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (rootIds.isEmpty()) return Collections.emptyMap();

        return feedRepository.findByIdsWithRelations(new ArrayList<>(rootIds)).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));
    }

    private FeedOverviewDto.FeedOverviewDtoBuilder buildBaseDto(Feed f, FeedRenderContext ctx, long repostCount) {
        return FeedOverviewDto.builder()
                .clubId(f.getClub() != null ? f.getClub().getClubId() : null)
                .feedId(f.getFeedId())
                .imageUrls(resolveImages(f))
                .likeCount(ctx.likeCountMap().getOrDefault(f.getFeedId(), f.getLikeCount()).intValue())
                .commentCount(ctx.commentCountMap().getOrDefault(f.getFeedId(), f.getCommentCount()).intValue())
                .profileImage(f.getUser() != null ? f.getUser().getProfileImage() : null)
                .nickname(f.getUser() != null ? f.getUser().getNickname() : null)
                .content(f.getContent())
                .isLiked(ctx.likedFeedIds().contains(f.getFeedId()))
                .isFeedMine(f.getUser() != null && Objects.equals(f.getUser().getUserId(), ctx.userId()))
                .created(f.getCreatedAt())
                .repostCount(repostCount);
    }

    private FeedOverviewDto toOverviewDto(Feed f, FeedRenderContext ctx) {
        long selfRepostCount = ctx.repostCntMap().getOrDefault(f.getFeedId(), 0L);
        FeedOverviewDto.FeedOverviewDtoBuilder b = buildBaseDto(f, ctx, selfRepostCount);

        Long parentId = f.getParentFeedId();
        if (parentId != null) {
            Feed p = ctx.parentMap().get(parentId);
            if (p != null) {
                long parentRepostCount = ctx.repostCntMap().getOrDefault(parentId, 0L);
                b.parentFeed(buildBaseDto(p, ctx, parentRepostCount).build());
            }
        }

        Long rootId = f.getRootFeedId();
        if (rootId != null) {
            Feed r = ctx.rootMap().get(rootId);
            if (r != null) {
                long rootRepostCount = ctx.repostCntMap().getOrDefault(rootId, 0L);
                b.rootFeed(buildBaseDto(r, ctx, rootRepostCount).build());
            }
        }

        return b.build();
    }

    private List<String> resolveImages(Feed f) {
        List<FeedImage> imgs = f.getFeedImages();
        if (imgs == null || imgs.isEmpty()) return Collections.emptyList();
        return imgs.stream()
                .map(FeedImage::getFeedImage)
                .filter(Objects::nonNull)
                .toList();
    }
}
