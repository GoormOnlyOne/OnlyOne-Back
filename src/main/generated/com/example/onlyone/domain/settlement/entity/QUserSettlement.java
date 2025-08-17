package com.example.onlyone.domain.settlement.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QUserSettlement is a Querydsl query type for UserSettlement
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QUserSettlement extends EntityPathBase<UserSettlement> {

    private static final long serialVersionUID = 1001743647L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QUserSettlement userSettlement = new QUserSettlement("userSettlement");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final DateTimePath<java.time.LocalDateTime> completedTime = createDateTime("completedTime", java.time.LocalDateTime.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final QSettlement settlement;

    public final EnumPath<SettlementStatus> settlementStatus = createEnum("settlementStatus", SettlementStatus.class);

    public final com.example.onlyone.domain.user.entity.QUser user;

    public final NumberPath<Long> userSettlementId = createNumber("userSettlementId", Long.class);

    public QUserSettlement(String variable) {
        this(UserSettlement.class, forVariable(variable), INITS);
    }

    public QUserSettlement(Path<? extends UserSettlement> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QUserSettlement(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QUserSettlement(PathMetadata metadata, PathInits inits) {
        this(UserSettlement.class, metadata, inits);
    }

    public QUserSettlement(Class<? extends UserSettlement> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.settlement = inits.isInitialized("settlement") ? new QSettlement(forProperty("settlement"), inits.get("settlement")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

