package com.example.onlyone.domain.club.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QClubLike is a Querydsl query type for ClubLike
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QClubLike extends EntityPathBase<ClubLike> {

    private static final long serialVersionUID = -1930964795L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QClubLike clubLike = new QClubLike("clubLike");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final QClub club;

    public final NumberPath<Long> clubLikeId = createNumber("clubLikeId", Long.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final com.example.onlyone.domain.user.entity.QUser user;

    public QClubLike(String variable) {
        this(ClubLike.class, forVariable(variable), INITS);
    }

    public QClubLike(Path<? extends ClubLike> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QClubLike(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QClubLike(PathMetadata metadata, PathInits inits) {
        this(ClubLike.class, metadata, inits);
    }

    public QClubLike(Class<? extends ClubLike> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.club = inits.isInitialized("club") ? new QClub(forProperty("club"), inits.get("club")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

