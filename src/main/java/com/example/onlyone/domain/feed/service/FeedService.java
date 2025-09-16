package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.*;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class FeedService {
    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;
    private final FeedCommentRepository feedCommentRepository;
    private final UserClubRepository userClubRepository;
    private final NotificationService notificationService;

    private final DefaultRedisScript<List> likeToggleScript;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public void createFeed(Long clubId, FeedRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
        UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_JOIN));
        Feed feed = requestDto.toEntity(club, user);

        requestDto.getFeedUrls().stream()
                .map(url -> FeedImage.builder()
                        .feedImage(url)
                        .feed(feed)
                        .build())
                .forEach(feed.getFeedImages()::add);
        feedRepository.save(feed);
    }

    public void updateFeed(Long clubId, Long feedId, FeedRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        if (!(user.getUserId().equals(feed.getUser().getUserId()))) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }
        updateFeedImage(feed, requestDto);
        feed.update(requestDto.getContent());
    }

    private void updateFeedImage(Feed feed, FeedRequestDto requestDto) {
        feed.getFeedImages().clear();
        requestDto.getFeedUrls().stream()
                .map(url -> FeedImage.builder()
                        .feedImage(url)
                        .feed(feed)
                        .build())
                .forEach(feed.getFeedImages()::add);
    }

    public boolean toggleLike(long clubId, long feedId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        long userId = userService.getCurrentUser().getUserId();
        String reqId = UUID.randomUUID().toString();

        List<String> keys = List.of(
                "feed:" + feedId + ":likers",
                "feed:" + feedId + ":like_count",
                "like:events",
                "idemp:" + reqId
        );
        Object[] args = {
                String.valueOf(userId),
                String.valueOf(feedId),
                reqId,
                String.valueOf(clock.millis())
        };

        List<?> raw = redis.execute(likeToggleScript, keys, args);
        if (raw == null || raw.size() < 3) throw new IllegalStateException("toggle script failed");

        // Redis가 숫자를 Long/Integer 등으로 줄 수 있으니 Number로 받아서 longValue()
        List<Long> r = new ArrayList<>(3);
        for (Object o : raw) r.add(((Number) o).longValue());

        boolean nowOn = r.get(0) == 1L; // 토글 후 현재 상태 (1이면 좋아요 ON, 0이면 OFF)
        // r.get(1) = delta: +1 또는 -1, r.get(2) = newCount: Redis 카운터의 최신 값
        return nowOn;
    }

    @Transactional(readOnly = true)
    public Page<FeedSummaryResponseDto> getFeedList(Long clubId, Pageable pageable) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        Page<Feed> feeds = feedRepository.findByClubAndParentFeedIdIsNull(club, pageable);

        return feeds.map(feed -> {
            String thumbnailUrl = null;
            List<FeedImage> imgs = feed.getFeedImages();
            if (imgs != null && !imgs.isEmpty()) {           // ★ 빈 리스트 가드
                thumbnailUrl = imgs.get(0).getFeedImage();
            }

            return new FeedSummaryResponseDto(
                    feed.getFeedId(),
                    thumbnailUrl,
                    feed.getFeedLikes().size(),
                    feed.getFeedComments().size()
            );
        });

    }

    @Transactional(readOnly = true)
    public FeedDetailResponseDto getFeedDetail(Long clubId, Long feedId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        Long currentUserId = userService.getCurrentUser().getUserId();

        List<String> imageUrls = feed.getFeedImages().stream()
                .map(FeedImage::getFeedImage)
                .collect(Collectors.toList());

        boolean isLiked = feed.getFeedLikes().stream()
                .anyMatch(like -> like.getUser().getUserId().equals(currentUserId));

        boolean isMine = feed.getUser().getUserId().equals(currentUserId);

        List<FeedCommentResponseDto> commentResponseDtos = feed.getFeedComments().stream()
                .map(comment -> FeedCommentResponseDto.from(comment, currentUserId))
                .collect(Collectors.toList());
        long repostCount = feedRepository.countByParentFeedId(feedId);

        return FeedDetailResponseDto.from(feed, imageUrls, isLiked, isMine, commentResponseDtos, repostCount);
    }


    public void createComment(Long clubId, Long feedId, FeedCommentRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        boolean isMember = userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId);
        if(!isMember) {
            throw new CustomException(ErrorCode.CLUB_NOT_JOIN);
        }
        FeedComment feedComment = requestDto.toEntity(feed, currentUser);
        feedCommentRepository.save(feedComment);
        if (!feed.getUser().getUserId().equals(currentUser.getUserId())) {
            notificationService.createNotification(feed.getUser(), Type.COMMENT, new String[]{currentUser.getNickname()});
        }
    }

    public void deleteComment(Long clubId, Long feedId, Long commentId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        FeedComment feedComment = feedCommentRepository.findById(commentId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMENT_NOT_FOUND));

        if (!feedComment.getFeed().getFeedId().equals(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }
        User user = userService.getCurrentUser();
        Long userId = user.getUserId();
        if (!(userId.equals(feedComment.getUser().getUserId()) ||
                userId.equals(feed.getUser().getUserId()))) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);
        }

        feedCommentRepository.delete(feedComment);
    }

    public void softDeleteFeed(Long clubId, Long feedId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed target = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        // 권한 체크
        Long me = userService.getCurrentUser().getUserId();
        if (!Objects.equals(target.getUser().getUserId(), me)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }

        // 1) 중간 노드 삭제 대비: 나를 parent로 참조하던 '직계 자식'들의 parent/root를 NULL
        feedRepository.clearParentAndRootForChildren(target.getFeedId());

        // 2) 루트 노드 삭제 대비: 나를 root로 참조하던 모든 후손들의 root를 NULL
        feedRepository.clearRootForDescendants(target.getFeedId());

        // 3) 내 행 소프트 삭제
        int affected = feedRepository.softDeleteById(target.getFeedId());
        if (affected == 0) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND); // 동시성 등으로 이미 삭제된 경우
        }
    }
}
