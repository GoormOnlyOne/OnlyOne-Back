package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class FeedService {
    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;

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
    public List<FeedSummaryResponseDto> getFeedList(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));

        List<Feed> feeds = feedRepository.findAllByClubOrderByCreatedAtDesc(club);

        return feeds.stream()
                .map(feed -> {
                    String thumbnailUrl = feed.getFeedImages().get(0).getFeedImage();

                    return new FeedSummaryResponseDto(
                            feed.getFeedId(),
                            thumbnailUrl,
                            feed.getFeedLikes().size()
                    );
                })
                .collect(Collectors.toList());
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

        return FeedDetailResponseDto.builder()
                .content(feed.getContent())
                .imageUrls(imageUrls)
                .likeCount(feed.getFeedLikes().size())
                .userId(feed.getUser().getUserId())
                .nickname(feed.getUser().getNickname())
                .profileImage(feed.getUser().getProfileImage())
                .updatedAt(feed.getModifiedAt())
                .isLiked(isLiked)
                .isMine(isMine)
                .build();
    }
}
