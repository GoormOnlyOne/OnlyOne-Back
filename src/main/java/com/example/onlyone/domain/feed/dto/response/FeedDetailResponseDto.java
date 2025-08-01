package com.example.onlyone.domain.feed.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class FeedDetailResponseDto {
    private String content;
    private List<String> imageUrls;
    private int likeCount;
    private int commentCount;

    private Long userId;
    private String nickname;
    private String profileImage;

    private LocalDateTime updatedAt;

    private boolean isLiked;
    private boolean isFeedMine;

    private List<FeedCommentResponseDto> comments;
}
