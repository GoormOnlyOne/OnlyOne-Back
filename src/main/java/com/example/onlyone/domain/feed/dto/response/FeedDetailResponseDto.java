package com.example.onlyone.domain.feed.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class FeedDetailResponseDto {
    private String content;
    private List<String> imageUrls;
    private int likeCount;
}
