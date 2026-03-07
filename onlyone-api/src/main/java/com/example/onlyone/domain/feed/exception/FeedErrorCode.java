package com.example.onlyone.domain.feed.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FeedErrorCode implements ErrorCode {

    FEED_NOT_FOUND(404, "FEED_404_1", "피드를 찾을 수 없습니다."),
    REFEED_DEPTH_LIMIT(409, "FEED_409_1", "리피드는 두 번까지만 가능합니다."),
    DUPLICATE_REFEED(409, "FEED_409_2", "같은 피드를 이미 공유한 클럽으로 리피드 할 수 없습니다."),
    UNAUTHORIZED_FEED_ACCESS(403, "FEED_403_1", "해당 피드에 대한 권한이 없습니다."),
    COMMENT_NOT_FOUND(404, "FEED_404_2", "댓글을 찾을 수 없습니다."),
    UNAUTHORIZED_COMMENT_ACCESS(403, "FEED_403_2", "해당 댓글에 대한 권한이 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
