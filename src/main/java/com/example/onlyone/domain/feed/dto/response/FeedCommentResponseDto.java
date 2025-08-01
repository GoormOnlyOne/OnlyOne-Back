package com.example.onlyone.domain.feed.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

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
}
