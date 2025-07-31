package com.example.onlyone.domain.feed.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Builder
@Getter
@AllArgsConstructor
public class FeedSummaryResponseDto {
    private Long feedId;
    private String thumbnailUrl;
    private int likeCount;
}
