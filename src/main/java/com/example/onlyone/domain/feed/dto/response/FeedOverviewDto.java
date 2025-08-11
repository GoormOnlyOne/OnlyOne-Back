package com.example.onlyone.domain.feed.dto.response;

import com.example.onlyone.domain.feed.entity.Feed;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class FeedOverviewDto {
    private Long feedId;
    private List<String> imageUrls;
    private int likeCount;
    private int commentCount;
    private String profileImage;
    private String nickname;
    private String content;
    private boolean isLiked;
    private boolean isFeedMine;
    private LocalDateTime created;

    @JsonInclude(Include.ALWAYS)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private FeedOverviewDto parentFeed;

    @JsonInclude(Include.ALWAYS)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private FeedOverviewDto rootFeed;
}
