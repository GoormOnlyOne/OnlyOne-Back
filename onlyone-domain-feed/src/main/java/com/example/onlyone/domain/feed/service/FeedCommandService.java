package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.request.RefeedRequestDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedType;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class FeedCommandService {

    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;
    private final UserClubRepository userClubRepository;

    public void createFeed(Long clubId, FeedRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
        userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_JOIN));
        Feed feed = requestDto.toEntity(club, user);
        feed.replaceImages(requestDto.feedUrls());
        feedRepository.save(feed);
        log.info("피드 생성: clubId={}, userId={}", clubId, user.getUserId());
    }

    public void updateFeed(Long clubId, Long feedId, FeedRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        if (!user.getUserId().equals(feed.getUser().getUserId())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }
        feed.replaceImages(requestDto.feedUrls());
        feed.update(requestDto.content());
        log.info("피드 수정: feedId={}, clubId={}", feedId, clubId);
    }

    public void softDeleteFeed(Long clubId, Long feedId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed target = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        Long me = userService.getCurrentUser().getUserId();
        if (!Objects.equals(target.getUser().getUserId(), me)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }

        feedRepository.clearParentAndRootForChildren(target.getFeedId());
        feedRepository.clearRootForDescendants(target.getFeedId());

        int affected = feedRepository.softDeleteById(target.getFeedId());
        if (affected == 0) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }
        log.info("피드 삭제: feedId={}, clubId={}", feedId, clubId);
    }

    public void createRefeed(Long parentFeedId, Long targetClubId, RefeedRequestDto requestDto) {
        User user = userService.getCurrentUser();

        Feed parent = feedRepository.findById(parentFeedId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));

        // 원본 피드가 속한 모임에 대한 접근 권한 확인
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), parent.getClub().getClubId())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }

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
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_REFEED);
        }
    }
}
