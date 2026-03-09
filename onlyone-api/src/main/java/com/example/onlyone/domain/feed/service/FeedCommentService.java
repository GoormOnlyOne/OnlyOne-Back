package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.port.FeedStoragePort;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.feed.exception.FeedErrorCode;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedCommentService {

    private final FeedRepository feedRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final FeedStoragePort feedStoragePort;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final FeedCacheService cache;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void createComment(Long clubId, Long feedId, FeedCommentRequestDto requestDto) {
        Feed feed = findFeedInClub(feedId, clubId);
        User currentUser = userService.getCurrentUser();
        validateMembership(currentUser.getUserId(), clubId);

        FeedComment feedComment = requestDto.toEntity(feed, currentUser);
        feedCommentRepository.save(feedComment);
        // comment_count 갱신을 TX 커밋 후 비동기로 처리 (feed row X-lock 제거)
        eventPublisher.publishEvent(new CommentCountEvent(feedId, 1));
        cache.invalidateDetail(feedId);
        log.info("댓글 생성: feedId={}, userId={}", feedId, currentUser.getUserId());
    }

    @Transactional
    public void deleteComment(Long clubId, Long feedId, Long commentId) {
        Feed feed = findFeedInClub(feedId, clubId);
        FeedComment feedComment = feedCommentRepository.findById(commentId)
                .orElseThrow(() -> new CustomException(FeedErrorCode.COMMENT_NOT_FOUND));
        if (!feedComment.getFeed().getFeedId().equals(feedId)) {
            throw new CustomException(FeedErrorCode.FEED_NOT_FOUND);
        }

        Long userId = userService.getCurrentUserId();
        if (!userId.equals(feedComment.getUser().getUserId()) && !userId.equals(feed.getUser().getUserId())) {
            throw new CustomException(FeedErrorCode.UNAUTHORIZED_COMMENT_ACCESS);
        }

        feedCommentRepository.delete(feedComment);
        eventPublisher.publishEvent(new CommentCountEvent(feedId, -1));
        cache.invalidateDetail(feedId);
        log.info("댓글 삭제: commentId={}, feedId={}, userId={}", commentId, feedId, userId);
    }

    @Transactional(readOnly = true)
    public List<FeedCommentResponseDto> getCommentList(Long feedId, Pageable pageable) {
        Long userId = userService.getCurrentUserId();
        return feedStoragePort.findCommentsByFeedId(feedId, pageable).stream()
                .map(c -> c.toDto(userId))
                .toList();
    }

    // ── event record ──

    public record CommentCountEvent(Long feedId, int delta) {}

    // ── private helpers ──

    private Feed findFeedInClub(Long feedId, Long clubId) {
        return feedRepository.findById(feedId)
                .filter(f -> f.getClub() != null && f.getClub().getClubId().equals(clubId))
                .orElseThrow(() -> new CustomException(FeedErrorCode.FEED_NOT_FOUND));
    }

    private void validateMembership(Long userId, Long clubId) {
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_JOIN);
        }
    }

}
