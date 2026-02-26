package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.ParentRepostCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FeedRenderService {

    private final FeedRepository feedRepository;
    private final FeedLikeRepository feedLikeRepository;

    private record RenderContext(
            Long userId,
            Set<Long> likedFeedIds,
            Map<Long, Feed> parentMap,
            Map<Long, Feed> rootMap,
            Map<Long, Long> repostCntMap,
            Map<Long, Long> likeCountMap,
            Map<Long, Long> commentCountMap
    ) {}

    public List<FeedOverviewDto> buildOverviewList(List<FeedIdWithCounts> pass1, Long userId) {
        if (pass1.isEmpty()) return Collections.emptyList();

        List<Long> feedIds = pass1.stream().map(FeedIdWithCounts::feedId).toList();
        Map<Long, Long> likeCountMap = new HashMap<>();
        Map<Long, Long> commentCountMap = new HashMap<>();
        for (FeedIdWithCounts row : pass1) {
            likeCountMap.put(row.feedId(), row.likeCount());
            commentCountMap.put(row.feedId(), row.commentCount());
        }

        Map<Long, Feed> feedMap = feedRepository.findByIdsWithRelations(feedIds).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));
        List<Feed> feeds = feedIds.stream()
                .map(feedMap::get)
                .filter(Objects::nonNull)
                .toList();

        RenderContext ctx = new RenderContext(
                userId,
                feedLikeRepository.findLikedFeedIdsByUser(feedIds, userId),
                bulkLoadByIds(feeds, Feed::getParentFeedId),
                bulkLoadByIds(feeds, Feed::getRootFeedId),
                countDirectReposts(feeds),
                likeCountMap,
                commentCountMap
        );

        return feeds.stream()
                .map(f -> toOverviewDto(f, ctx))
                .toList();
    }

    // ── private ──

    private Map<Long, Feed> bulkLoadByIds(List<Feed> feeds, Function<Feed, Long> idExtractor) {
        Set<Long> ids = feeds.stream()
                .map(idExtractor).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Collections.emptyMap();
        return feedRepository.findByIdsWithRelations(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));
    }

    private Map<Long, Long> countDirectReposts(List<Feed> feeds) {
        Set<Long> targetIds = new HashSet<>();
        for (Feed f : feeds) {
            targetIds.add(f.getFeedId());
            if (f.getRootFeedId() != null) targetIds.add(f.getRootFeedId());
        }
        if (targetIds.isEmpty()) return Collections.emptyMap();
        return feedRepository.countDirectRepostsIn(new ArrayList<>(targetIds)).stream()
                .collect(Collectors.toMap(ParentRepostCount::parentId, ParentRepostCount::cnt));
    }

    private FeedOverviewDto toOverviewDto(Feed f, RenderContext ctx) {
        long selfRepostCount = ctx.repostCntMap().getOrDefault(f.getFeedId(), 0L);
        FeedOverviewDto.FeedOverviewDtoBuilder b = buildBaseDto(f, ctx, selfRepostCount);

        Long parentId = f.getParentFeedId();
        if (parentId != null) {
            Feed p = ctx.parentMap().get(parentId);
            if (p != null) {
                b.parentFeed(buildBaseDto(p, ctx, ctx.repostCntMap().getOrDefault(parentId, 0L)).build());
            }
        }

        Long rootId = f.getRootFeedId();
        if (rootId != null) {
            Feed r = ctx.rootMap().get(rootId);
            if (r != null) {
                b.rootFeed(buildBaseDto(r, ctx, ctx.repostCntMap().getOrDefault(rootId, 0L)).build());
            }
        }

        return b.build();
    }

    private FeedOverviewDto.FeedOverviewDtoBuilder buildBaseDto(Feed f, RenderContext ctx, long repostCount) {
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

    private List<String> resolveImages(Feed f) {
        List<FeedImage> imgs = f.getFeedImages();
        if (imgs == null || imgs.isEmpty()) return Collections.emptyList();
        return imgs.stream().map(FeedImage::getFeedImage).filter(Objects::nonNull).toList();
    }
}
