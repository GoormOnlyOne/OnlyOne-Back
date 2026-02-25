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
        // validation: club 별도 조회 제거 → feed 1쿼리 + 멤버십 1쿼리 (3→2 SELECT)
        Feed feed = feedRepository.findById(feedId)
                .filter(f -> f.getClub() != null && f.getClub().getClubId().equals(clubId))
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        boolean isMember = userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId);
        if (!isMember) {
            throw new CustomException(ErrorCode.CLUB_NOT_JOIN);
        }
        // 1) 댓글 INSERT (feed 행 lock 불필요 — FK 참조만)
        FeedComment feedComment = requestDto.toEntity(feed, currentUser);
        transactionTemplate.executeWithoutResult(status -> {
            feedCommentRepository.save(feedComment);
        });
        // 2) count 증가를 별도 트랜잭션으로 분리 — feed X lock 보유 최소화
        //    like_count UPDATE와의 lock 경합 시간을 나노초 수준으로 축소
        try {
            transactionTemplate.executeWithoutResult(status -> {
                feedRepository.incrementCommentCount(feedId);
            });
        } catch (Exception e) {
            // count 동기화 실패해도 댓글은 보존 (sync_feed_counts 프로시저로 보정 가능)
            log.warn("댓글 카운트 증가 실패 (댓글은 정상 저장됨): feedId={}, err={}", feedId, e.getMessage());
        }
        log.info("댓글 생성: feedId={}, userId={}", feedId, userId);
    }

    public void deleteComment(Long clubId, Long feedId, Long commentId) {
        // validation: club 별도 조회 제거 → feed 1쿼리 (3→2 SELECT)
        Feed feed = feedRepository.findById(feedId)
                .filter(f -> f.getClub() != null && f.getClub().getClubId().equals(clubId))
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        FeedComment feedComment = feedCommentRepository.findById(commentId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMENT_NOT_FOUND));
        if (!feedComment.getFeed().getFeedId().equals(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }

        Long userId = userService.getCurrentUser().getUserId();
        boolean isCommentAuthor = userId.equals(feedComment.getUser().getUserId());
        boolean isFeedAuthor = userId.equals(feed.getUser().getUserId());
        if (!isCommentAuthor && !isFeedAuthor) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);
        }

        // 1) 댓글 DELETE (feed 행 lock 불필요)
        transactionTemplate.executeWithoutResult(status -> {
            feedCommentRepository.delete(feedComment);
        });
        // 2) count 감소를 별도 트랜잭션 — feed X lock 최소화
        try {
            transactionTemplate.executeWithoutResult(status -> {
                feedRepository.decrementCommentCount(feedId);
            });
        } catch (Exception e) {
            log.warn("댓글 카운트 감소 실패 (댓글은 정상 삭제됨): feedId={}, err={}", feedId, e.getMessage());
        }
        log.info("댓글 삭제: commentId={}, feedId={}, userId={}", commentId, feedId, userId);
    }

    @Transactional(readOnly = true)
    public List<FeedCommentResponseDto> getCommentList(Long feedId, Pageable pageable) {
        if (!feedRepository.existsById(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }
        Long userId = userService.getCurrentUser().getUserId();

        return feedCommentRepository.findByFeedIdWithUser(feedId, pageable)
                .stream()
                .map(c -> FeedCommentResponseDto.from(c, userId))
                .toList();
    }
}
