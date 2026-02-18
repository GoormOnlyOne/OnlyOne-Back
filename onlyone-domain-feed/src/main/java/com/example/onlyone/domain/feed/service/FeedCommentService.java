package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
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

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedCommentService {

    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;

    @Transactional
    public void createComment(Long clubId, Long feedId, FeedCommentRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Feed feed = feedRepository.findByFeedIdAndClub(feedId, club)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        User currentUser = userService.getCurrentUser();
        Long userId = currentUser.getUserId();
        boolean isMember = userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId);
        if (!isMember) {
            throw new CustomException(ErrorCode.CLUB_NOT_JOIN);
        }
        FeedComment feedComment = requestDto.toEntity(feed, currentUser);
        feedCommentRepository.save(feedComment);
        feedRepository.incrementCommentCount(feedId);
        log.info("댓글 생성: feedId={}, userId={}", feedId, userId);
    }

    @Transactional
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

        Long userId = userService.getCurrentUser().getUserId();
        boolean isCommentAuthor = userId.equals(feedComment.getUser().getUserId());
        boolean isFeedAuthor = userId.equals(feed.getUser().getUserId());
        if (!isCommentAuthor && !isFeedAuthor) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);
        }

        feedCommentRepository.delete(feedComment);
        feedRepository.decrementCommentCount(feedId);
        log.info("댓글 삭제: commentId={}, feedId={}, userId={}", commentId, feedId, userId);
    }

    @Transactional(readOnly = true)
    public List<FeedCommentResponseDto> getCommentList(Long feedId, Pageable pageable) {
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new CustomException(ErrorCode.FEED_NOT_FOUND));
        Long userId = userService.getCurrentUser().getUserId();

        return feedCommentRepository.findByFeedOrderByCreatedAt(feed, pageable)
                .stream()
                .map(c -> FeedCommentResponseDto.from(c, userId))
                .toList();
    }
}
