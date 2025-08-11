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
import com.example.onlyone.domain.feed.repository.FeedRepository;
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
@Transactional
@RequiredArgsConstructor
public class FeedMainService {
    private static final int MAX_REFEED_DEPTH = 2;

    private final FeedRepository feedRepository;
    private final UserService userService;
    private final UserClubRepository userClubRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final ClubRepository clubRepository;

    @Transactional(readOnly = true)
    public List<FeedOverviewDto> getPersonalFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();
        List<UserClub> myJoinClubs = userClubRepository.findByUserUserId(userId);
        List<Long> myClubIds = myJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .toList();

        List<Long> memberIds = userClubRepository.findUserIdByClubIds(myClubIds);
        List<UserClub> friendMemberJoinClubs = userClubRepository.findByUserUserIdIn(memberIds);
        List<Long> friendClubIds = friendMemberJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .filter(id -> !myClubIds.contains(id))
                .toList();

        List<Long> allClubIds = new ArrayList<>(myClubIds);
        allClubIds.addAll(friendClubIds);

        List<Feed> feeds = feedRepository.findByClubIds(allClubIds, pageable);

        // 5) 부모/루트 미리 벌크 로드해서 N+1 방지
        Set<Long> parentIds = feeds.stream()
                .map(f -> f.getParent() != null ? f.getParent().getFeedId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> rootIds = feeds.stream()
                .map(Feed::getRootFeedId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Feed> parentMap = parentIds.isEmpty()
                ? Collections.emptyMap()
                : feedRepository.findAllById(parentIds).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));

        Map<Long, Feed> rootMap = rootIds.isEmpty()
                ? Collections.emptyMap()
                : feedRepository.findAllById(rootIds).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));

       Set<Long> likedFeedIds = Collections.emptySet();

        return feeds.stream()
                .map(f -> toOverviewDto(f, userId, likedFeedIds, parentMap, rootMap))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedOverviewDto> getPopularFeed(Pageable pageable) {
        Long userId = userService.getCurrentUser().getUserId();
        List<UserClub> myJoinClubs = userClubRepository.findByUserUserId(userId);
        List<Long> myClubIds = myJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .toList();

        List<Long> memberIds = userClubRepository.findUserIdByClubIds(myClubIds);
        List<UserClub> friendMemberJoinClubs = userClubRepository.findByUserUserIdIn(memberIds);
        List<Long> friendClubIds = friendMemberJoinClubs.stream()
                .map(uc -> uc.getClub().getClubId())
                .filter(id -> !myClubIds.contains(id))
                .toList();

        List<Long> allClubIds = new ArrayList<>(myClubIds);
        allClubIds.addAll(friendClubIds);

        List<Feed> feeds = feedRepository.findPopularByClubIds(allClubIds, pageable);

        // 5) 부모/루트 미리 벌크 로드해서 N+1 방지
        Set<Long> parentIds = feeds.stream()
                .map(f -> f.getParent() != null ? f.getParent().getFeedId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> rootIds = feeds.stream()
                .map(Feed::getRootFeedId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Feed> parentMap = parentIds.isEmpty()
                ? Collections.emptyMap()
                : feedRepository.findAllById(parentIds).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));

        Map<Long, Feed> rootMap = rootIds.isEmpty()
                ? Collections.emptyMap()
                : feedRepository.findAllById(rootIds).stream()
                .collect(Collectors.toMap(Feed::getFeedId, Function.identity()));

        Set<Long> likedFeedIds = Collections.emptySet();

        // 7) 매핑
        return feeds.stream()
                .map(f -> toOverviewDto(f, userId, likedFeedIds, parentMap, rootMap))
                .toList();
    }

    /** parent/root 용 얕은 매핑(안에 parentFeed/rootFeed는 세팅 안 함) */
    private FeedOverviewDto toShallowDto(Feed f, Long currentUserId, Set<Long> likedFeedIds) {
        return FeedOverviewDto.builder()
                .clubId(f.getClub() != null ? f.getClub().getClubId() : null)
                .feedId(f.getFeedId())
                .imageUrls(resolveImages(f))
                .likeCount(safeSize(f.getFeedLikes()))
                .commentCount(safeSize(f.getFeedComments()))
                .profileImage(f.getUser() != null ? f.getUser().getProfileImage() : null)
                .nickname(f.getUser() != null ? f.getUser().getNickname() : null)
                .content(f.getContent())
                .isLiked(isLiked(f, currentUserId, likedFeedIds))
                .isFeedMine(f.getUser() != null && Objects.equals(f.getUser().getUserId(), currentUserId))
                .created(f.getCreatedAt())
                .parentFeed(null)
                .rootFeed(null)
                .build();
    }

    private FeedOverviewDto toOverviewDto(
            Feed f,
            Long currentUserId,
            Set<Long> likedFeedIds,
            Map<Long, Feed> parentMap,
            Map<Long, Feed> rootMap
    ) {
        // 기본 필드
        FeedOverviewDto.FeedOverviewDtoBuilder b = FeedOverviewDto.builder()
                .clubId(f.getClub() != null ? f.getClub().getClubId() : null)
                .feedId(f.getFeedId())
                .imageUrls(resolveImages(f))
                .likeCount(safeSize(f.getFeedLikes()))
                .commentCount(safeSize(f.getFeedComments()))
                .profileImage(f.getUser() != null ? f.getUser().getProfileImage() : null)
                .nickname(f.getUser() != null ? f.getUser().getNickname() : null)
                .content(f.getContent())
                .isLiked(isLiked(f, currentUserId, likedFeedIds))
                .isFeedMine(f.getUser() != null && Objects.equals(f.getUser().getUserId(), currentUserId))
                .created(f.getCreatedAt());

        Feed parent = f.getParent();
        if (parent != null) {
            Feed p = parentMap.getOrDefault(parent.getFeedId(), parent); // 영속성 컨텍스트에 이미 있을 수도
            b.parentFeed(toShallowDto(p, currentUserId, likedFeedIds));
        }

        Long rootId = f.getRootFeedId();
        if (rootId != null) {
            Feed r = rootMap.get(rootId);
            if (r != null) {
                b.rootFeed(toShallowDto(r, currentUserId, likedFeedIds));
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
        if (likedFeedIds != null && !likedFeedIds.isEmpty()) {
            return likedFeedIds.contains(f.getFeedId());
        }

        List<FeedLike> likes = f.getFeedLikes();
        if (likes == null) return false;
        return likes.stream().anyMatch(l -> l.getUser() != null && Objects.equals(l.getUser().getUserId(), userId));
    }

    @Transactional(readOnly = true)
    public List<FeedCommentResponseDto> getCommentList(Long feedId, Pageable pageable) {
        Feed feed = feedRepository.findById(feedId)
                        .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        Long userId = userService.getCurrentUser().getUserId();

        return  feedCommentRepository.findByFeedOrderByCreatedAtDesc(feed,pageable)
                .stream()
                .map(c -> FeedCommentResponseDto.from(c,userId))
                .toList();
    }

    @Transactional
    public void createRefeed(Long parentFeedId, Long targetClubId, RefeedRequestDto requestDto) {
        User user = userService.getCurrentUser();

        Feed parent = feedRepository.findById(parentFeedId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        int newDepth = parent.getDepth() + 1;
        if (newDepth > MAX_REFEED_DEPTH) {
            throw new CustomException(ErrorCode.REFEED_DEPTH_LIMIT);
        }

        Club club = clubRepository.findById(targetClubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_JOIN));

        Long rootId = (parent.getRootFeedId() != null)
                ? parent.getRootFeedId()
                : parent.getFeedId();

        Feed reFeed = Feed.builder()
                .content(requestDto.getContent())
                .feedType(FeedType.REFEED)
                .parent(parent)
                .rootFeedId(rootId)
                .depth(newDepth)
                .club(club)
                .user(user)
                .build();

        try {
            feedRepository.save(reFeed);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_REFEED);
        }
    }

}
