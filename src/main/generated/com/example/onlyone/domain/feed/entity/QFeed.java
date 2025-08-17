package com.example.onlyone.domain.feed.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QFeed is a Querydsl query type for Feed
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QFeed extends EntityPathBase<Feed> {

    private static final long serialVersionUID = -508507682L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QFeed feed = new QFeed("feed");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final com.example.onlyone.domain.club.entity.QClub club;

    public final StringPath content = createString("content");

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final NumberPath<Integer> depth = createNumber("depth", Integer.class);

    public final ListPath<FeedComment, QFeedComment> feedComments = this.<FeedComment, QFeedComment>createList("feedComments", FeedComment.class, QFeedComment.class, PathInits.DIRECT2);

    public final NumberPath<Long> feedId = createNumber("feedId", Long.class);

    public final ListPath<FeedImage, QFeedImage> feedImages = this.<FeedImage, QFeedImage>createList("feedImages", FeedImage.class, QFeedImage.class, PathInits.DIRECT2);

    public final ListPath<FeedLike, QFeedLike> feedLikes = this.<FeedLike, QFeedLike>createList("feedLikes", FeedLike.class, QFeedLike.class, PathInits.DIRECT2);

    public final EnumPath<FeedType> feedType = createEnum("feedType", FeedType.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final QFeed parent;

    public final NumberPath<Long> rootFeedId = createNumber("rootFeedId", Long.class);

    public final com.example.onlyone.domain.user.entity.QUser user;

    public QFeed(String variable) {
        this(Feed.class, forVariable(variable), INITS);
    }

    public QFeed(Path<? extends Feed> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QFeed(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QFeed(PathMetadata metadata, PathInits inits) {
        this(Feed.class, metadata, inits);
    }

    public QFeed(Class<? extends Feed> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.club = inits.isInitialized("club") ? new com.example.onlyone.domain.club.entity.QClub(forProperty("club"), inits.get("club")) : null;
        this.parent = inits.isInitialized("parent") ? new QFeed(forProperty("parent"), inits.get("parent")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

