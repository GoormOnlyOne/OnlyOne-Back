package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
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

import java.util.Objects;

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
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        if (!Objects.equals(user.getUserId(), feed.getUser().getUserId())) {
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
}
