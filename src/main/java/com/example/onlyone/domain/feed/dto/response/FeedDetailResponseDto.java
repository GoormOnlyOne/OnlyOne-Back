package com.example.onlyone.domain.feed.dto.response;

import com.example.onlyone.domain.feed.entity.Feed;
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

    public static FeedDetailResponseDto from(Feed feed, List<String> imageUrls, boolean isLiked, boolean isFeedMine, List<FeedCommentResponseDto> comments) {
        return FeedDetailResponseDto.builder()
                .content(feed.getContent())
                .imageUrls(imageUrls)
                .likeCount(feed.getFeedLikes().size())
                .commentCount(feed.getFeedComments().size())
                .userId(feed.getUser().getUserId())
                .nickname(feed.getUser().getNickname())
                .profileImage(feed.getUser().getProfileImage())
                .updatedAt(feed.getModifiedAt())
                .isLiked(isLiked)
                .isFeedMine(isFeedMine)
                .comments(comments)
                .build();
    }
}
