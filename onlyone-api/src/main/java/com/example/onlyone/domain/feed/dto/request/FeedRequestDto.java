package com.example.onlyone.domain.feed.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.List;

public record FeedRequestDto(
        @NotNull
        @Size(min = 1, max = 5, message = "이미지는 최소 1개 이상 최대 5개까지입니다.")
        List<@NotBlank(message = "이미지 URL은 비어있을 수 없습니다.")
             @URL(message = "유효한 URL 형식이어야 합니다.")
             String> feedUrls,

        @Size(max = 50, message = "피드 설명은 {max}자 이내여야 합니다.")
        String content
) {
    public Feed toEntity(Club club, User user) {
        return Feed.builder()
                .club(club)
                .user(user)
                .content(content)
                .build();
    }
}
