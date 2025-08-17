package com.example.onlyone.domain.club.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QClub is a Querydsl query type for Club
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QClub extends EntityPathBase<Club> {

    private static final long serialVersionUID = -221223922L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QClub club = new QClub("club");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final ListPath<com.example.onlyone.domain.chat.entity.ChatRoom, com.example.onlyone.domain.chat.entity.QChatRoom> chatRooms = this.<com.example.onlyone.domain.chat.entity.ChatRoom, com.example.onlyone.domain.chat.entity.QChatRoom>createList("chatRooms", com.example.onlyone.domain.chat.entity.ChatRoom.class, com.example.onlyone.domain.chat.entity.QChatRoom.class, PathInits.DIRECT2);

    public final StringPath city = createString("city");

    public final NumberPath<Long> clubId = createNumber("clubId", Long.class);

    public final StringPath clubImage = createString("clubImage");

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final StringPath description = createString("description");

    public final StringPath district = createString("district");

    public final ListPath<com.example.onlyone.domain.feed.entity.Feed, com.example.onlyone.domain.feed.entity.QFeed> feeds = this.<com.example.onlyone.domain.feed.entity.Feed, com.example.onlyone.domain.feed.entity.QFeed>createList("feeds", com.example.onlyone.domain.feed.entity.Feed.class, com.example.onlyone.domain.feed.entity.QFeed.class, PathInits.DIRECT2);

    public final com.example.onlyone.domain.interest.entity.QInterest interest;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final StringPath name = createString("name");

    public final ListPath<com.example.onlyone.domain.schedule.entity.Schedule, com.example.onlyone.domain.schedule.entity.QSchedule> schedules = this.<com.example.onlyone.domain.schedule.entity.Schedule, com.example.onlyone.domain.schedule.entity.QSchedule>createList("schedules", com.example.onlyone.domain.schedule.entity.Schedule.class, com.example.onlyone.domain.schedule.entity.QSchedule.class, PathInits.DIRECT2);

    public final NumberPath<Integer> userLimit = createNumber("userLimit", Integer.class);

    public QClub(String variable) {
        this(Club.class, forVariable(variable), INITS);
    }

    public QClub(Path<? extends Club> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QClub(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QClub(PathMetadata metadata, PathInits inits) {
        this(Club.class, metadata, inits);
    }

    public QClub(Class<? extends Club> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.interest = inits.isInitialized("interest") ? new com.example.onlyone.domain.interest.entity.QInterest(forProperty("interest")) : null;
    }

}

