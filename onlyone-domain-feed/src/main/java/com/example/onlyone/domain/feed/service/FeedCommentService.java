package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedCommentService {

    private final FeedRepository feedRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final TransactionTemplate transactionTemplate;

    public void createComment(Long clubId, Long feedId, FeedCommentRequestDto requestDto) {
        Feed feed = findFeedInClub(feedId, clubId);
        User currentUser = userService.getCurrentUser();
        validateMembership(currentUser.getUserId(), clubId);

        FeedComment feedComment = requestDto.toEntity(feed, currentUser);
        runInTx(() -> feedCommentRepository.save(feedComment));
        updateCountSafely(() -> feedRepository.incrementCommentCount(feedId),
                "댓글 카운트 증가 실패 (댓글은 정상 저장됨)", feedId);
        log.info("댓글 생성: feedId={}, userId={}", feedId, currentUser.getUserId());
    }

    public void deleteComment(Long clubId, Long feedId, Long commentId) {
        Feed feed = findFeedInClub(feedId, clubId);
        FeedComment feedComment = feedCommentRepository.findById(commentId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMENT_NOT_FOUND));
        if (!feedComment.getFeed().getFeedId().equals(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }

        Long userId = userService.getCurrentUserId();
        if (!userId.equals(feedComment.getUser().getUserId()) && !userId.equals(feed.getUser().getUserId())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);
        }

        runInTx(() -> feedCommentRepository.delete(feedComment));
        updateCountSafely(() -> feedRepository.decrementCommentCount(feedId),
                "댓글 카운트 감소 실패 (댓글은 정상 삭제됨)", feedId);
        log.info("댓글 삭제: commentId={}, feedId={}, userId={}", commentId, feedId, userId);
    }

    @Transactional(readOnly = true)
    public List<FeedCommentResponseDto> getCommentList(Long feedId, Pageable pageable) {
        if (!feedRepository.existsById(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }
        Long userId = userService.getCurrentUserId();
        return feedCommentRepository.findByFeedIdWithUser(feedId, pageable).stream()
                .map(c -> FeedCommentResponseDto.from(c, userId))
                .toList();
    }

    // ── private helpers ──

    private Feed findFeedInClub(Long feedId, Long clubId) {
        return feedRepository.findById(feedId)
                .filter(f -> f.getClub() != null && f.getClub().getClubId().equals(clubId))
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
    }

    private void validateMembership(Long userId, Long clubId) {
        if (!userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_JOIN);
        }
    }

    private void runInTx(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    private void updateCountSafely(Runnable action, String failMsg, Long feedId) {
        try {
            runInTx(action);
        } catch (Exception e) {
            log.warn("{}: feedId={}, err={}", failMsg, feedId, e.getMessage());
        }
    }
}
