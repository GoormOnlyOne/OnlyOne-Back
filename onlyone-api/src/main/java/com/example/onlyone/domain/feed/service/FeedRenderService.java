package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.port.FeedStoragePort;
import com.example.onlyone.domain.feed.port.FeedStoragePort.FeedDetailItem;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FeedRenderService {

    private final FeedStoragePort feedStoragePort;

    private record RenderContext(
            Long userId,
            Set<Long> likedFeedIds,
            Map<Long, FeedDetailItem> parentMap,
            Map<Long, FeedDetailItem> rootMap,
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

        Map<Long, FeedDetailItem> feedMap = feedStoragePort.findFeedsByIdsWithRelations(feedIds).stream()
                .collect(Collectors.toMap(FeedDetailItem::feedId, Function.identity()));
        List<FeedDetailItem> feeds = feedIds.stream()
                .map(feedMap::get)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, FeedDetailItem> relatedMap = bulkLoadRelatedFeeds(feeds);

        RenderContext ctx = new RenderContext(
                userId,
                feedStoragePort.findLikedFeedIdsByUser(feedIds, userId),
                relatedMap,
                relatedMap,
                countDirectReposts(feeds),
                likeCountMap,
                commentCountMap
        );

        return feeds.stream()
                .map(f -> toOverviewDto(f, ctx))
                .toList();
    }

    // ── private ──

    private Map<Long, FeedDetailItem> bulkLoadRelatedFeeds(List<FeedDetailItem> feeds) {
        Set<Long> ids = new HashSet<>();
        for (FeedDetailItem f : feeds) {
            if (f.parentFeedId() != null) ids.add(f.parentFeedId());
            if (f.rootFeedId() != null) ids.add(f.rootFeedId());
        }
        if (ids.isEmpty()) return Collections.emptyMap();
        return feedStoragePort.findFeedsByIdsWithRelations(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(FeedDetailItem::feedId, Function.identity()));
    }

    private Map<Long, Long> countDirectReposts(List<FeedDetailItem> feeds) {
        Set<Long> targetIds = new HashSet<>();
        for (FeedDetailItem f : feeds) {
            targetIds.add(f.feedId());
            if (f.rootFeedId() != null) targetIds.add(f.rootFeedId());
        }
        if (targetIds.isEmpty()) return Collections.emptyMap();
        return feedStoragePort.countDirectRepostsInBatch(new ArrayList<>(targetIds));
    }

    private FeedOverviewDto toOverviewDto(FeedDetailItem f, RenderContext ctx) {
        long selfRepostCount = ctx.repostCntMap().getOrDefault(f.feedId(), 0L);
        FeedOverviewDto.FeedOverviewDtoBuilder b = buildBaseDto(f, ctx, selfRepostCount);

        Long parentId = f.parentFeedId();
        if (parentId != null) {
            FeedDetailItem p = ctx.parentMap().get(parentId);
            if (p != null) {
                b.parentFeed(buildBaseDto(p, ctx, ctx.repostCntMap().getOrDefault(parentId, 0L)).build());
            }
        }

        Long rootId = f.rootFeedId();
        if (rootId != null) {
            FeedDetailItem r = ctx.rootMap().get(rootId);
            if (r != null) {
                b.rootFeed(buildBaseDto(r, ctx, ctx.repostCntMap().getOrDefault(rootId, 0L)).build());
            }
        }

        return b.build();
    }

    private FeedOverviewDto.FeedOverviewDtoBuilder buildBaseDto(FeedDetailItem f, RenderContext ctx, long repostCount) {
        return FeedOverviewDto.builder()
                .clubId(f.clubId())
                .feedId(f.feedId())
                .imageUrls(f.imageUrls() != null ? f.imageUrls() : Collections.emptyList())
                .likeCount(ctx.likeCountMap().getOrDefault(f.feedId(), f.likeCount()).intValue())
                .commentCount(ctx.commentCountMap().getOrDefault(f.feedId(), f.commentCount()).intValue())
                .profileImage(f.profileImage())
                .nickname(f.nickname())
                .content(f.content())
                .isLiked(ctx.likedFeedIds().contains(f.feedId()))
                .isFeedMine(f.userId() != null && Objects.equals(f.userId(), ctx.userId()))
                .created(f.createdAt())
                .repostCount(repostCount);
    }
}
