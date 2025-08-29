package com.example.onlyone.domain.chat.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QUserChatRoom is a Querydsl query type for UserChatRoom
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QUserChatRoom extends EntityPathBase<UserChatRoom> {

    private static final long serialVersionUID = 661103160L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QUserChatRoom userChatRoom = new QUserChatRoom("userChatRoom");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final EnumPath<ChatRole> chatRole = createEnum("chatRole", ChatRole.class);

    public final QChatRoom chatRoom;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final com.example.onlyone.domain.user.entity.QUser user;

    public final NumberPath<Long> userChatRoomId = createNumber("userChatRoomId", Long.class);

    public QUserChatRoom(String variable) {
        this(UserChatRoom.class, forVariable(variable), INITS);
    }

    public QUserChatRoom(Path<? extends UserChatRoom> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QUserChatRoom(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QUserChatRoom(PathMetadata metadata, PathInits inits) {
        this(UserChatRoom.class, metadata, inits);
    }

    public QUserChatRoom(Class<? extends UserChatRoom> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.chatRoom = inits.isInitialized("chatRoom") ? new QChatRoom(forProperty("chatRoom"), inits.get("chatRoom")) : null;
        this.user = inits.isInitialized("user") ? new com.example.onlyone.domain.user.entity.QUser(forProperty("user")) : null;
    }

}

