package com.example.onlyone.domain.notification.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QAppNotification is a Querydsl query type for AppNotification
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QAppNotification extends EntityPathBase<AppNotification> {

    private static final long serialVersionUID = -812757889L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QAppNotification appNotification = new QAppNotification("appNotification");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final StringPath content = createString("content");

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final BooleanPath fcmSent = createBoolean("fcmSent");

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final BooleanPath isRead = createBoolean("isRead");

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final QNotificationType notificationType;

    public final NumberPath<Long> targetId = createNumber("targetId", Long.class);

    public final StringPath targetType = createString("targetType");

    public final com.example.onlyone.domain.user.entity.QUser user;

    public QAppNotification(String variable) {
        this(AppNotification.class, forVariable(variable), INITS);
    }

    public QAppNotification(Path<? extends AppNotification> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QAppNotification(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QAppNotification(PathMetadata metadata, PathInits inits) {
        this(AppNotification.class, metadata, inits);
    }

    public QAppNotification(Class<? extends AppNotification> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.notificationType = inits.isInitialized("notificationType") ? new QNotificationType(forProperty("notificationType")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

