package com.example.onlyone.domain.feed.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MongoDB 피드 도큐먼트 — 비정규화된 단일 문서 모델.
 * User, Club 정보를 임베딩하여 JOIN 없이 단일 조회로 처리.
 */
@Document(collection = "feed")
@CompoundIndexes({
        @CompoundIndex(name = "idx_club_deleted_created", def = "{'clubId': 1, 'deleted': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "idx_club_deleted_parent", def = "{'clubId': 1, 'deleted': 1, 'parentFeedId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "idx_parent_deleted", def = "{'parentFeedId': 1, 'deleted': 1}"),
        @CompoundIndex(name = "idx_deleted_created_score", def = "{'deleted': 1, 'clubId': 1, 'createdAt': -1, 'likeCount': 1, 'commentCount': 1}"),
        @CompoundIndex(name = "idx_personal_feed", def = "{'deleted': 1, 'createdAt': -1, 'clubId': 1}")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedDocument {

    @Id
    private String id;

    private Long feedId;
    private String content;
    private String feedType;

    // 비정규화된 클럽 정보
    private Long clubId;
    private String clubName;

    // 비정규화된 유저 정보
    private Long userId;
    private String nickname;
    private String profileImage;

    // 리피드 참조
    private Long parentFeedId;
    private Long rootFeedId;

    // 비정규화 카운트
    @Builder.Default
    private Long likeCount = 0L;
    @Builder.Default
    private Long commentCount = 0L;

    // 이미지 URL 임베딩
    private List<String> imageUrls;

    // 좋아요 유저 ID 임베딩 (워밍업 대체)
    private List<Long> likerUserIds;

    // 댓글 임베딩 (최근 N개만)
    private List<EmbeddedComment> recentComments;

    private boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
    private LocalDateTime deletedAt;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbeddedComment {
        private Long commentId;
        private Long userId;
        private String nickname;
        private String profileImage;
        private String content;
        private LocalDateTime createdAt;
    }
}
