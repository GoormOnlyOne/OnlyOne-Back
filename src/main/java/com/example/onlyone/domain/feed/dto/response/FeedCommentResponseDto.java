package com.example.onlyone.domain.feed.dto.response;

import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Builder
@Getter
@AllArgsConstructor
public class FeedCommentResponseDto {
    private Long commentId;
    private Long userId;
    private String nickname;
    private String profileImage;
    private String content;
    private LocalDateTime createdAt;
    private boolean isCommentMine;

    public static FeedCommentResponseDto from(FeedComment comment, Long userId) {
        return FeedCommentResponseDto.builder()
                .commentId(comment.getFeedCommentId())
                .userId(comment.getUser().getUserId())
                .nickname(comment.getUser().getNickname())
                .profileImage(comment.getUser().getProfileImage())
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt())
                .isCommentMine(comment.getUser().getUserId().equals(userId))
                .build();
    }
}
