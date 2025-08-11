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
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.entity.FeedLike;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class FeedService {
    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;
    private final FeedLikeRepository feedLikeRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final UserClubRepository userClubRepository;
    private final NotificationService notificationService;


    public void createFeed(Long clubId, FeedRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
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

    @Transactional(readOnly = true)
    public Page<FeedSummaryResponseDto> getFeedList(Long clubId, Pageable pageable) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        Page<Feed> feeds = feedRepository.findByClubAndParentIsNull(club, pageable);

        return feeds.map(feed -> {
                    String thumbnailUrl = feed.getFeedImages().get(0).getFeedImage();

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

        return FeedDetailResponseDto.from(feed, imageUrls, isLiked, isMine, commentResponseDtos);
    }

    public boolean toggleLike(Long clubId, Long feedId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        User currentUser = userService.getCurrentUser();

        Optional<FeedLike> checkLike = feedLikeRepository.findByFeedAndUser(feed, currentUser);

        if(checkLike.isPresent()) {
            feedLikeRepository.delete(checkLike.get());
            return false;
        } else {
            FeedLike feedLike = FeedLike.builder()
                    .feed(feed)
                    .user(currentUser)
                    .build();
            feedLikeRepository.save(feedLike);
            int likeCount = feedLikeRepository.countByFeed(feed);
            if(likeCount > 0) likeCount--;
            // 본인 글에 대한 알림 방지
            if (!feed.getUser().getUserId().equals(currentUser.getUserId())) {
                    notificationService.createNotification(feed.getUser(), Type.LIKE, new String[]{ currentUser.getNickname(), String.valueOf(likeCount) });
            }
            return true;
        }
    }

    public void createComment(Long clubId, Long feedId, FeedCommentRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        User currentUser = userService.getCurrentUser();

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

    public void deleteFeed(Long clubId, Long feedId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        User user = userService.getCurrentUser();
        if (!(user.getUserId().equals(feed.getUser().getUserId()))) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }
        feedRepository.delete(feed);
    }
}
