package com.example.onlyone.domain.user.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QUserInterest is a Querydsl query type for UserInterest
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QUserInterest extends EntityPathBase<UserInterest> {

    private static final long serialVersionUID = -589443582L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QUserInterest userInterest = new QUserInterest("userInterest");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final com.example.onlyone.domain.interest.entity.QInterest interest;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final QUser user;

    public final NumberPath<Long> userInterestId = createNumber("userInterestId", Long.class);

    public QUserInterest(String variable) {
        this(UserInterest.class, forVariable(variable), INITS);
    }

    public QUserInterest(Path<? extends UserInterest> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QUserInterest(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QUserInterest(PathMetadata metadata, PathInits inits) {
        this(UserInterest.class, metadata, inits);
    }

    public QUserInterest(Class<? extends UserInterest> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.interest = inits.isInitialized("interest") ? new com.example.onlyone.domain.interest.entity.QInterest(forProperty("interest")) : null;
        this.user = inits.isInitialized("user") ? new QUser(forProperty("user")) : null;
    }

}

