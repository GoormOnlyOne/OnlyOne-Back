package com.example.onlyone.domain.feed.dto.response;

import com.example.onlyone.domain.feed.entity.Feed;

import java.time.LocalDateTime;
import java.util.List;

public record FeedDetailResponseDto(
    Long feedId,
    String content,
    List<String> imageUrls,
    int likeCount,
    int commentCount,
    Long repostCount,
    Long userId,
    String nickname,
    String profileImage,
    LocalDateTime updatedAt,
    boolean isLiked,
    boolean isFeedMine,
    List<FeedCommentResponseDto> comments
) {
    public static FeedDetailResponseDto from(Feed feed, List<String> imageUrls, boolean isLiked, boolean isFeedMine, List<FeedCommentResponseDto> comments, long repostCount) {
        return new FeedDetailResponseDto(
                feed.getFeedId(),
                feed.getContent(),
                imageUrls,
                feed.getLikeCount().intValue(),
                feed.getCommentCount().intValue(),
                repostCount,
                feed.getUser().getUserId(),
                feed.getUser().getNickname(),
                feed.getUser().getProfileImage(),
                feed.getModifiedAt(),
                isLiked,
                isFeedMine,
                comments
        );
    }
}
