package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.request.RefeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.entity.FeedLike;
import com.example.onlyone.domain.feed.entity.FeedType;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@Log4j2
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FeedMainService {

    private final FeedRepository feedRepository;
    private final FeedLikeRepository feedLikeRepository;
    private final UserService userService;
    private final UserClubRepository userClubRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final ClubRepository clubRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        return getFeedsCommon(pageable, false);
    }

    @Transactional(readOnly = true)
    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        return getFeedsCommon(pageable, true);
    }

    private List<FeedOverviewDto> getFeedsCommon(Pageable pageable, boolean popular) {
        Long userId = userService.getCurrentUser().getUserId();

        List<Long> clubIds = resolveAccessibleClubIds(userId);
        if (clubIds.isEmpty()) return Collections.emptyList();

        // Pass 1: ID + likeCount + commentCount 한번에 조회 (DB 라운드트립 축소)
        List<FeedRepository.FeedIdWithCounts> pass1 = popular
                ? feedRepository.findPopularFeedIdsWithCountsByClubIds(clubIds, pageable)
                : feedRepository.findFeedIdsWithCountsByClubIds(clubIds, pageable);
        if (pass1.isEmpty()) return Collections.emptyList();

        List<Long> feedIds = pass1.stream().map(FeedRepository.FeedIdWithCounts::getFeedId).toList();
        Map<Long, Long> likeCountMap = new HashMap<>();
        Map<Long, Long> commentCountMap = new HashMap<>();
        for (FeedRepository.FeedIdWithCounts row : pass1) {
            likeCountMap.put(row.getFeedId(), row.getLikeCount());
            commentCountMap.put(row.getFeedId(), row.getCommentCount());
        }

        // Pass 2: JOIN FETCH로 엔티티 + user + images 한번에 로딩 (N+1 제거)
        List<Feed> feedsUnordered = feedRepository.findByIdsWithRelations(feedIds);
        Map<Long, Feed> feedMap = feedsUnordered.stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));
        List<Feed> feeds = feedIds.stream()
                .map(feedMap::get)
                .filter(Objects::nonNull)
                .toList();

        // Pass 3: repost count + liked status (2 쿼리)
        Map<Long, Long> repostCntMap = countDirectReposts(feeds);
        Map<Long, Feed> parentMap = bulkLoadParents(feeds);
        Map<Long, Feed> rootMap   = bulkLoadRoots(feeds);
        Set<Long> likedFeedIds = feedLikeRepository.findLikedFeedIdsByUser(feedIds, userId);

        return feeds.stream()
                .map(f -> toOverviewDto(
                        f,
                        userId,
                        likedFeedIds,
                        parentMap,
                        rootMap,
                        repostCntMap,
                        likeCountMap,
                        commentCountMap))
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

    private FeedOverviewDto toShallowDto(Feed f, Long currentUserId, Set<Long> likedFeedIds, Long repostCount,
                                         Map<Long, Long> likeCountMap, Map<Long, Long> commentCountMap) {
        return FeedOverviewDto.builder()
                .clubId(f.getClub() != null ? f.getClub().getClubId() : null)
                .feedId(f.getFeedId())
                .imageUrls(resolveImages(f))
                .likeCount(likeCountMap.getOrDefault(f.getFeedId(), f.getLikeCount()).intValue())
                .commentCount(commentCountMap.getOrDefault(f.getFeedId(), f.getCommentCount()).intValue())
                .profileImage(f.getUser() != null ? f.getUser().getProfileImage() : null)
                .nickname(f.getUser() != null ? f.getUser().getNickname() : null)
                .content(f.getContent())
                .isLiked(isLiked(f, currentUserId, likedFeedIds))
                .isFeedMine(f.getUser() != null && Objects.equals(f.getUser().getUserId(), currentUserId))
                .created(f.getCreatedAt())
                .repostCount(repostCount)
                .parentFeed(null)
                .rootFeed(null)
                .build();
    }

    private FeedOverviewDto toOverviewDto(
            Feed f,
            Long currentUserId,
            Set<Long> likedFeedIds,
            Map<Long, Feed> parentMap,
            Map<Long, Feed> rootMap,
            Map<Long, Long> repostCntMap,
            Map<Long, Long> likeCountMap,
            Map<Long, Long> commentCountMap
    ) {
        long selfRepostCount = repostCntMap.getOrDefault(f.getFeedId(), 0L);

        FeedOverviewDto.FeedOverviewDtoBuilder b = FeedOverviewDto.builder()
                .clubId(f.getClub() != null ? f.getClub().getClubId() : null)
                .feedId(f.getFeedId())
                .imageUrls(resolveImages(f))
                .likeCount(likeCountMap.getOrDefault(f.getFeedId(), f.getLikeCount()).intValue())
                .commentCount(commentCountMap.getOrDefault(f.getFeedId(), f.getCommentCount()).intValue())
                .profileImage(f.getUser() != null ? f.getUser().getProfileImage() : null)
                .nickname(f.getUser() != null ? f.getUser().getNickname() : null)
                .content(f.getContent())
                .isLiked(isLiked(f, currentUserId, likedFeedIds))
                .isFeedMine(f.getUser() != null && Objects.equals(f.getUser().getUserId(), currentUserId))
                .created(f.getCreatedAt())
                .repostCount(selfRepostCount);

        Long parentId = f.getParentFeedId();
        if (parentId != null) {
            Feed p = parentMap.get(parentId);
            if (p != null) {
                long parentRepostCount = repostCntMap.getOrDefault(parentId, 0L);
                b.parentFeed(toShallowDto(p, currentUserId, likedFeedIds, parentRepostCount, likeCountMap, commentCountMap));
            }
        }

        Long rootId = f.getRootFeedId();
        if (rootId != null) {
            Feed r = rootMap.get(rootId);
            if (r != null) {
                long rootRepostCount = repostCntMap.getOrDefault(rootId, 0L);
                b.rootFeed(toShallowDto(r, currentUserId, likedFeedIds, rootRepostCount, likeCountMap, commentCountMap));
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

    private int safeSize(Collection<?> c) {
        return c == null ? 0 : c.size();
    }

    private boolean isLiked(Feed f, Long userId, Set<Long> likedFeedIds) {
        // 배치 로드된 likedFeedIds를 사용 (lazy loading 방지)
        if (likedFeedIds != null) {
            return likedFeedIds.contains(f.getFeedId());
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<FeedCommentResponseDto> getCommentList(Long feedId, Pageable pageable) {
        Feed feed = feedRepository.findById(feedId)
                        .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        Long userId = userService.getCurrentUser().getUserId();

        return  feedCommentRepository.findByFeedOrderByCreatedAt(feed,pageable)
                .stream()
                .map(c -> FeedCommentResponseDto.from(c,userId))
                .toList();
    }

    @Transactional(readOnly = false)
    public void createRefeed(Long parentFeedId, Long targetClubId, RefeedRequestDto requestDto) {
        User user = userService.getCurrentUser();

        Feed parent = feedRepository.findById(parentFeedId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        Club club = clubRepository.findById(targetClubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_JOIN));

        Long rootId = (parent.getRootFeedId() != null)
                ? parent.getRootFeedId()
                : parent.getFeedId();

        Feed reFeed = Feed.builder()
                .content(requestDto.content())
                .feedType(FeedType.REFEED)
                .parentFeedId(parentFeedId)
                .rootFeedId(rootId)
                .club(club)
                .user(user)
                .build();

        try {
            feedRepository.save(reFeed);

            // 원본 피드 작성자에게 리피드 알림 발송 (자신이 리피드한 경우 제외)
            User originalAuthor = parent.getUser();
//            if (!originalAuthor.getUserId().equals(user.getUserId())) {
//                notificationService.createNotification(
//                    originalAuthor,
//                    Type.REFEED,
//                    user.getNickname()   // 리피드한 사용자 닉네임
//                );
//                log.info("Refeed notification sent: originalAuthor={}, refeedUser={}",
//                    originalAuthor.getUserId(), user.getUserId());
//            }
//
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_REFEED);
        }
    }
}
