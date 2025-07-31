package com.example.onlyone.domain.feed.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.user.entity.User;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.util.List;

@Getter
public class FeedRequestDto {

    @NotNull
    @Size(min = 1, message = "이미지는 최소 1개 이상 등록해야 합니다.")
    private List<String> feedUrls;

    @Size(max = 50, message = "피드 설명은 {max}자 이내여야 합니다.")
    private String content;

    public Feed toEntity(Club club, User user) {
        return Feed.builder()
                .club(club)
                .user(user)
                .content(content)
                .build();
    }
}
