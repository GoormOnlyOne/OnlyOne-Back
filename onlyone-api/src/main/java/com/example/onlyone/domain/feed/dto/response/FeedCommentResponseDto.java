package com.example.onlyone.domain.feed.dto.response;

import com.example.onlyone.domain.feed.entity.FeedComment;

import java.time.LocalDateTime;

public record FeedCommentResponseDto(
    Long commentId,
    Long userId,
    String nickname,
    String profileImage,
    String content,
    LocalDateTime createdAt,
    boolean isCommentMine
) {
    public static FeedCommentResponseDto from(FeedComment comment, Long userId) {
        return new FeedCommentResponseDto(
                comment.getFeedCommentId(),
                comment.getUser().getUserId(),
                comment.getUser().getNickname(),
                comment.getUser().getProfileImage(),
                comment.getContent(),
                comment.getCreatedAt(),
                comment.getUser().getUserId().equals(userId)
        );
    }
}
