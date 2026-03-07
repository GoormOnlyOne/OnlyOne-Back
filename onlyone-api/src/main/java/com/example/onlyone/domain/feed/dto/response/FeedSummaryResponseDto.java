package com.example.onlyone.domain.feed.dto.response;

public record FeedSummaryResponseDto(
    Long feedId,
    String thumbnailUrl,
    int likeCount,
    int commentCount
) {
}
