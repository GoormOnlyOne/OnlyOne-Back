package com.example.onlyone.domain.club.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QUserClub is a Querydsl query type for UserClub
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QUserClub extends EntityPathBase<UserClub> {

    private static final long serialVersionUID = 848853625L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QUserClub userClub = new QUserClub("userClub");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final QClub club;

    public final EnumPath<ClubRole> clubRole = createEnum("clubRole", ClubRole.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final com.example.onlyone.domain.user.entity.QUser user;

    public final NumberPath<Long> userClubId = createNumber("userClubId", Long.class);

    public QUserClub(String variable) {
        this(UserClub.class, forVariable(variable), INITS);
    }

    public QUserClub(Path<? extends UserClub> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QUserClub(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QUserClub(PathMetadata metadata, PathInits inits) {
        this(UserClub.class, metadata, inits);
    }

    public QUserClub(Class<? extends UserClub> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.club = inits.isInitialized("club") ? new QClub(forProperty("club"), inits.get("club")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

