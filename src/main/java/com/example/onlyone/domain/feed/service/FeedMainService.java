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
import com.example.onlyone.domain.feed.entity.FeedType;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.notification.entity.Type;
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

import java.util.ArrayList;
import java.util.List;



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
    private final NotificationService notificationService;

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

        return feeds.stream()
                .map(feed -> FeedOverviewDto.builder()
                        .feedId(feed.getFeedId())
                        .thumbnailUrl(resolveThumbnail(feed))
                        .likeCount(feed.getFeedLikes().size())
                        .commentCount(feed.getFeedComments().size())
                        .profileImage(feed.getUser().getProfileImage())
                        .build()
                )
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

        return feeds.stream()
                .map(f -> FeedOverviewDto.builder()
                        .feedId(f.getFeedId())
                        .thumbnailUrl(resolveThumbnail(f))
                        .likeCount(f.getFeedLikes().size())
                        .commentCount(f.getFeedComments().size())
                        .profileImage(f.getUser().getProfileImage())
                        .created(f.getCreatedAt())
                        .build()
                )
                .toList();
    }

    private String resolveThumbnail(Feed feed) {
        if (feed.getFeedImages() != null && !feed.getFeedImages().isEmpty()) {
            return feed.getFeedImages().get(0).getFeedImage();
        }
        return null;
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
            
            // 원본 피드 작성자에게 리피드 알림 발송 (자신이 리피드한 경우 제외)
            User originalAuthor = parent.getUser();
            if (!originalAuthor.getUserId().equals(user.getUserId())) {
                notificationService.createNotification(
                    originalAuthor,
                    Type.REFEED,
                    user.getNickname()   // 리피드한 사용자 닉네임
                );
                log.info("Refeed notification sent: originalAuthor={}, refeedUser={}", 
                    originalAuthor.getUserId(), user.getUserId());
            }
            
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_REFEED);
        }
    }

}
