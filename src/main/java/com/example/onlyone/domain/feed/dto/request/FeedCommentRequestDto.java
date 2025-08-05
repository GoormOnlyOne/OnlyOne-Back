package com.example.onlyone.domain.feed.dto.request;

import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class FeedCommentRequestDto {
    @NotBlank
    @Size(max = 50, message = "댓글은 50자 이내여야 합니다.")
    private String content;

    public FeedComment toEntity(Feed feed, User user) {
        return FeedComment.builder()
                .content(content)
                .feed(feed)
                .user(user)
                .build();
    }
}
