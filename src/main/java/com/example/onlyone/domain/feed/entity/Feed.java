package com.example.onlyone.domain.feed.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "feed",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_refeed_once",
                        columnNames = {"user_id", "parent_feed_id", "club_id"}
                )
        })
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_feed_id")
    private Feed parent;

    @Column(name = "root_feed_id")
    private Long rootFeedId;

    @Builder.Default
    @Column(name = "depth")
    @NotNull
    private int depth = 0;

    @Builder.Default
    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FeedComment> feedComments = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FeedLike> feedLikes = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FeedImage> feedImages = new ArrayList<>();

    public void update(String content) {
        this.content = content;
    }
}