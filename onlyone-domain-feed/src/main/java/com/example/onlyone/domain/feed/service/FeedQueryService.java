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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final EntityManager entityManager;
    private final StringRedisTemplate redis;

    /** IN clause 청크 크기 */
    private static final int CLUB_CHUNK_SIZE = 5;
    /** pass1 캐시 TTL (30초) */
    private static final Duration PASS1_CACHE_TTL = Duration.ofSeconds(30);
    /** 캐싱 대상 최대 페이지 번호 — 딥 페이지는 캐시 키 폭발 방지를 위해 제외 */
    private static final int MAX_CACHEABLE_PAGE = 5;
    private static final String PERSONAL_FEED_CACHE_PREFIX = "pf:";
    private static final String POPULAR_FEED_CACHE_PREFIX = "ppf:";

    /** 인메모리 결과 캐시 (직렬화 비용 0) — TTL 10초 */
    private static final long RESULT_CACHE_TTL_MS = 10_000;
    private static final int MAX_RESULT_CACHE_SIZE = 2000;
    private record CachedResult(List<FeedOverviewDto> data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
    private static final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();

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

    /** 피드 상세 공통 데이터 캐시 (isLiked/isFeedMine 제외, feedId 기준) */
    private record DetailCacheEntry(
            Feed feed, List<String> imageUrls,
            List<FeedCommentResponseDto> comments, long repostCount,
            long expiresAt
    ) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
    private static final ConcurrentHashMap<Long, DetailCacheEntry> detailCache = new ConcurrentHashMap<>();
    private static final long DETAIL_CACHE_TTL_MS = 5_000;

    public FeedDetailResponseDto getFeedDetail(Long clubId, Long feedId) {
        Long currentUserId = userService.getCurrentUser().getUserId();

        // 인메모리 캐시에서 공통 데이터 조회
        DetailCacheEntry cached = detailCache.get(feedId);
        if (cached != null && !cached.isExpired()) {
            boolean isLiked = feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedId, currentUserId);
            boolean isMine = cached.feed().getUser().getUserId().equals(currentUserId);
            // 댓글의 isCommentMine은 유저별로 다르므로 재매핑
            List<FeedCommentResponseDto> userComments = cached.comments().stream()
                    .map(c -> new FeedCommentResponseDto(c.commentId(), c.userId(), c.nickname(), c.profileImage(), c.content(), c.createdAt(),
                            c.userId().equals(currentUserId)))
                    .toList();
            return FeedDetailResponseDto.from(cached.feed(), cached.imageUrls(), isLiked, isMine, userComments, cached.repostCount());
        }

        // 캐시 미스 — DB에서 로드
        Feed feed = feedRepository.findByIdAndClubIdWithRelations(feedId, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        List<String> imageUrls = feed.getFeedImages().stream()
                .map(FeedImage::getFeedImage)
                .collect(Collectors.toList());

        boolean isLiked = feedLikeRepository.existsByFeed_FeedIdAndUser_UserId(feedId, currentUserId);
        boolean isMine = feed.getUser().getUserId().equals(currentUserId);

        Pageable commentPage = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt"));
        List<FeedCommentResponseDto> commentResponseDtos = feedCommentRepository.findByFeedIdWithUser(feedId, commentPage).stream()
                .map(comment -> FeedCommentResponseDto.from(comment, currentUserId))
                .collect(Collectors.toList());
        long repostCount = feedRepository.countByParentFeedId(feedId);

        // 공통 데이터 캐싱 (크기 제한)
        if (detailCache.size() > MAX_RESULT_CACHE_SIZE) {
            detailCache.entrySet().removeIf(e -> e.getValue().isExpired());
        }
        detailCache.put(feedId, new DetailCacheEntry(feed, imageUrls, commentResponseDtos, repostCount,
                System.currentTimeMillis() + DETAIL_CACHE_TTL_MS));

        return FeedDetailResponseDto.from(feed, imageUrls, isLiked, isMine, commentResponseDtos, repostCount);
    }

    // ── 전체 피드 (3-pass 최적화) ──

    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();
        boolean cacheable = pageable.getPageNumber() <= MAX_CACHEABLE_PAGE;

        // 인메모리 결과 캐시 조회
        String resultKey = cacheable ? "pf:" + userId + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize() : null;
        List<FeedOverviewDto> cached = getResultCache(resultKey);
        if (cached != null) return cached;

        List<Long> clubIds = resolveAccessibleClubIds(userId);
        if (clubIds.isEmpty()) return Collections.emptyList();

        String pass1Key = cacheable
                ? PERSONAL_FEED_CACHE_PREFIX + userId + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize()
                : null;

        List<FeedRepository.FeedIdWithCounts> pass1 = cacheable ? getCachedPass1(pass1Key) : null;

        if (pass1 == null) {
            if (clubIds.size() <= CLUB_CHUNK_SIZE) {
                pass1 = feedRepository.findFeedIdsWithCountsByClubIds(clubIds, pageable);
            } else {
                pass1 = findPersonalFeedChunked(clubIds, pageable);
            }
            if (cacheable) cachePass1(pass1Key, pass1);
        }
        List<FeedOverviewDto> result = buildOverviewList(pass1, userId);
        putResultCache(resultKey, result);
        return result;
    }

    /**
     * 클럽 ID를 CLUB_CHUNK_SIZE 개씩 쪼개서 UNION ALL로 합친 뒤
     * 외부 ORDER BY + LIMIT으로 최종 결과 추출.
     * 각 서브쿼리는 (club_id, deleted, created_at) 커버링 인덱스를 활용.
     */
    @SuppressWarnings("unchecked")
    private List<FeedRepository.FeedIdWithCounts> findPersonalFeedChunked(
            List<Long> clubIds, Pageable pageable) {
        int limit = (int) pageable.getOffset() + pageable.getPageSize();
        List<List<Long>> chunks = partitionList(clubIds, CLUB_CHUNK_SIZE);

        // UNION ALL 동적 SQL 생성
        StringBuilder sql = new StringBuilder("SELECT feedId, likeCount, commentCount FROM (");
        int paramIdx = 1;
        Map<String, Object> paramMap = new HashMap<>();

        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sql.append(" UNION ALL ");
            List<Long> chunk = chunks.get(i);
            String paramName = "c" + i;
            sql.append("(SELECT f.feed_id as feedId, f.like_count as likeCount, ")
               .append("f.comment_count as commentCount, f.created_at as createdAt ")
               .append("FROM feed f WHERE f.club_id IN (:").append(paramName)
               .append(") AND f.deleted = false ORDER BY f.created_at DESC LIMIT ")
               .append(limit).append(")");
            paramMap.put(paramName, chunk);
        }
        sql.append(") t ORDER BY createdAt DESC LIMIT :offset, :pageSize");
        paramMap.put("offset", (int) pageable.getOffset());
        paramMap.put("pageSize", pageable.getPageSize());

        Query query = entityManager.createNativeQuery(sql.toString());
        paramMap.forEach(query::setParameter);

        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> (FeedRepository.FeedIdWithCounts) new FeedRepository.FeedIdWithCounts() {
                    private final Long fid = ((Number) row[0]).longValue();
                    private final Long lc = ((Number) row[1]).longValue();
                    private final Long cc = ((Number) row[2]).longValue();
                    @Override public Long getFeedId() { return fid; }
                    @Override public Long getLikeCount() { return lc; }
                    @Override public Long getCommentCount() { return cc; }
                })
                .toList();
    }

    private static <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();
        boolean cacheable = pageable.getPageNumber() <= MAX_CACHEABLE_PAGE;

        String resultKey = cacheable ? "ppf:" + userId + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize() : null;
        List<FeedOverviewDto> cached = getResultCache(resultKey);
        if (cached != null) return cached;

        List<Long> clubIds = resolveAccessibleClubIds(userId);
        if (clubIds.isEmpty()) return Collections.emptyList();

        String pass1Key = cacheable
                ? POPULAR_FEED_CACHE_PREFIX + userId + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize()
                : null;

        List<FeedRepository.FeedIdWithCounts> pass1 = cacheable ? getCachedPass1(pass1Key) : null;

        if (pass1 == null) {
            pass1 = feedRepository.findPopularFeedIdsWithCountsByClubIds(clubIds, pageable);
            if (cacheable) cachePass1(pass1Key, pass1);
        }
        List<FeedOverviewDto> result = buildOverviewList(pass1, userId);
        putResultCache(resultKey, result);
        return result;
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

    /** Redis에서 pass1 캐시 조회. 미스/에러 시 null */
    private List<FeedRepository.FeedIdWithCounts> getCachedPass1(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isEmpty()) return null;
            return parsePass1Cache(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** pass1 결과를 경량 문자열로 Redis에 캐싱 */
    private void cachePass1(String key, List<FeedRepository.FeedIdWithCounts> pass1) {
        try {
            if (pass1.isEmpty()) return;
            String value = pass1.stream()
                    .map(r -> r.getFeedId() + ":" + r.getLikeCount() + ":" + r.getCommentCount())
                    .collect(Collectors.joining(","));
            redis.opsForValue().set(key, value, PASS1_CACHE_TTL);
        } catch (Exception e) {
            log.debug("pass1 캐시 저장 실패: {}", e.getMessage());
        }
    }

    private List<FeedRepository.FeedIdWithCounts> parsePass1Cache(String raw) {
        return Arrays.stream(raw.split(","))
                .map(entry -> {
                    String[] parts = entry.split(":");
                    long fid = Long.parseLong(parts[0]);
                    long lc = Long.parseLong(parts[1]);
                    long cc = Long.parseLong(parts[2]);
                    return (FeedRepository.FeedIdWithCounts) new FeedRepository.FeedIdWithCounts() {
                        @Override public Long getFeedId() { return fid; }
                        @Override public Long getLikeCount() { return lc; }
                        @Override public Long getCommentCount() { return cc; }
                    };
                })
                .toList();
    }

    private List<FeedOverviewDto> getResultCache(String key) {
        if (key == null) return null;
        CachedResult cr = resultCache.get(key);
        if (cr == null || cr.isExpired()) return null;
        return cr.data();
    }

    private void putResultCache(String key, List<FeedOverviewDto> result) {
        if (key == null) return;
        // 캐시 크기 제한 — 초과 시 만료된 항목 정리
        if (resultCache.size() > MAX_RESULT_CACHE_SIZE) {
            resultCache.entrySet().removeIf(e -> e.getValue().isExpired());
        }
        resultCache.put(key, new CachedResult(result, System.currentTimeMillis() + RESULT_CACHE_TTL_MS));
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
