package com.example.onlyone.domain.feed.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class FeedOverviewDto {
    private Long feedId;
    private String thumbnailUrl;
    private int likeCount;
    private int commentCount;
    private String profileImage;
}
