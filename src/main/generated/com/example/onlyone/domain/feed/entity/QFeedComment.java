package com.example.onlyone.domain.feed.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QFeedComment is a Querydsl query type for FeedComment
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QFeedComment extends EntityPathBase<FeedComment> {

    private static final long serialVersionUID = 1437501089L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QFeedComment feedComment = new QFeedComment("feedComment");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final StringPath content = createString("content");

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final QFeed feed;

    public final NumberPath<Long> feedCommentId = createNumber("feedCommentId", Long.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final com.example.onlyone.domain.user.entity.QUser user;

    public QFeedComment(String variable) {
        this(FeedComment.class, forVariable(variable), INITS);
    }

    public QFeedComment(Path<? extends FeedComment> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QFeedComment(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QFeedComment(PathMetadata metadata, PathInits inits) {
        this(FeedComment.class, metadata, inits);
    }

    public QFeedComment(Class<? extends FeedComment> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.feed = inits.isInitialized("feed") ? new QFeed(forProperty("feed"), inits.get("feed")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

