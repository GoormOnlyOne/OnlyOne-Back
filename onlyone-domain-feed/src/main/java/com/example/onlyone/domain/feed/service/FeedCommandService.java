package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.request.RefeedRequestDto;
import com.example.onlyone.domain.feed.entity.Feed;
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
        Club club = findClubOrThrow(clubId);
        User user = userService.getCurrentUser();
        validateClubMembership(user.getUserId(), clubId);

        Feed feed = requestDto.toEntity(club, user);
        feed.replaceImages(requestDto.feedUrls());
        feedRepository.save(feed);
        log.info("피드 생성: clubId={}, userId={}", clubId, user.getUserId());
    }

    public void updateFeed(Long clubId, Long feedId, FeedRequestDto requestDto) {
        Club club = findClubOrThrow(clubId);
        User user = userService.getCurrentUser();
        Feed feed = findFeedByClubOrThrow(feedId, club);
        validateFeedOwnership(feed, user.getUserId());

        feed.replaceImages(requestDto.feedUrls());
        feed.update(requestDto.content());
        log.info("피드 수정: feedId={}, clubId={}", feedId, clubId);
    }

    public void softDeleteFeed(Long clubId, Long feedId) {
        Club club = findClubOrThrow(clubId);
        Feed target = findFeedByClubOrThrow(feedId, club);
        validateFeedOwnership(target, userService.getCurrentUserId());

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
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), parent.getClub().getClubId())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }

        Club targetClub = findClubOrThrow(targetClubId);
        validateClubMembership(user.getUserId(), targetClubId);

        try {
            feedRepository.save(parent.createRefeed(requestDto.content(), targetClub, user));
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_REFEED);
        }
    }

    // ── 검증 헬퍼 ──

    private Club findClubOrThrow(Long clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
    }

    private Feed findFeedByClubOrThrow(Long feedId, Club club) {
        return feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
    }

    private void validateClubMembership(Long userId, Long clubId) {
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_JOIN);
        }
    }

    private void validateFeedOwnership(Feed feed, Long userId) {
        if (!Objects.equals(feed.getUser().getUserId(), userId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
        }
    }
}
