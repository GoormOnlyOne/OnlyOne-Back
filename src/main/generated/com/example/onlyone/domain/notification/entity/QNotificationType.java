package com.example.onlyone.domain.notification.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;


/**
 * QNotificationType is a Querydsl query type for NotificationType
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QNotificationType extends EntityPathBase<NotificationType> {

    private static final long serialVersionUID = 1837619794L;

    public static final QNotificationType notificationType = new QNotificationType("notificationType");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    public final EnumPath<DeliveryMethod> deliveryMethod = createEnum("deliveryMethod", DeliveryMethod.class);

    public final NumberPath<Long> id = createNumber("id", Long.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final StringPath template = createString("template");

    public final EnumPath<Type> type = createEnum("type", Type.class);

    public QNotificationType(String variable) {
        super(NotificationType.class, forVariable(variable));
    }

    public QNotificationType(Path<? extends NotificationType> path) {
        super(path.getType(), path.getMetadata());
    }

    public QNotificationType(PathMetadata metadata) {
        super(NotificationType.class, metadata);
    }

}

