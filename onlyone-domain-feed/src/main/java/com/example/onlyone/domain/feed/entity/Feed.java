package com.example.onlyone.domain.feed.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "feed",
        indexes = {
                @Index(name = "uq_refeed_once_alive", columnList = "user_id, club_id, active_parent", unique = true)
        }
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
@SQLDelete(sql = "UPDATE feed SET deleted = true, deleted_at = now() WHERE feed_id = ?")
@SQLRestriction("deleted = false")
public class Feed extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feed_id", updatable = false)
    private Long feedId;

    @Column(name = "content")
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id")
    @NotNull
    private Club club;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    @NotNull
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    @NotNull
    @Builder.Default
    private FeedType feedType = FeedType.ORIGINAL;

    @Column(name = "parent_feed_id")
    private Long parentFeedId;

    @Column(name = "root_feed_id")
    private Long rootFeedId;

    @Column(name = "like_count", nullable = false)
    @Builder.Default
    private Long likeCount = 0L;

    @Column(name = "comment_count", nullable = false)
    @Builder.Default
    private Long commentCount = 0L;

    @Builder.Default
    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<FeedComment> feedComments = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<FeedLike> feedLikes = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<FeedImage> feedImages = new ArrayList<>();

    public void update(String content) {
        this.content = content;
    }

    public void replaceImages(List<String> urls) {
        this.feedImages.clear();
        urls.stream()
                .map(url -> FeedImage.builder().feedImage(url).feed(this).build())
                .forEach(this.feedImages::add);
    }

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(
            name = "active_parent",
            insertable = false, updatable = false,
            columnDefinition =
                    "BIGINT GENERATED ALWAYS AS (" +
                            "  CASE WHEN deleted = 0 AND parent_feed_id IS NOT NULL THEN parent_feed_id " +
                            "       ELSE NULL " +
                            "  END" +
                            ") STORED"
    )
    private Long activeParent;
}