package com.example.onlyone.domain.interest.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QInterest is a Querydsl query type for Interest
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QInterest extends EntityPathBase<Interest> {

    private static final long serialVersionUID = 1524804214L;

    public static final QInterest interest = new QInterest("interest");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final EnumPath<Category> category = createEnum("category", Category.class);

    public final ListPath<com.example.onlyone.domain.club.entity.Club, com.example.onlyone.domain.club.entity.QClub> clubs = this.<com.example.onlyone.domain.club.entity.Club, com.example.onlyone.domain.club.entity.QClub>createList("clubs", com.example.onlyone.domain.club.entity.Club.class, com.example.onlyone.domain.club.entity.QClub.class, PathInits.DIRECT2);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final NumberPath<Long> interestId = createNumber("interestId", Long.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public QInterest(String variable) {
        super(Interest.class, forVariable(variable));
    }

    public QInterest(Path<? extends Interest> path) {
        super(path.getType(), path.getMetadata());
    }

    public QInterest(PathMetadata metadata) {
        super(Interest.class, metadata);
    }

}

