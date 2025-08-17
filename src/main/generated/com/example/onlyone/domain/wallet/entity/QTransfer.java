package com.example.onlyone.domain.wallet.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QTransfer is a Querydsl query type for Transfer
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QTransfer extends EntityPathBase<Transfer> {

    private static final long serialVersionUID = 1832007494L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QTransfer transfer = new QTransfer("transfer");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final NumberPath<Long> transferId = createNumber("transferId", Long.class);

    public final com.example.onlyone.domain.settlement.entity.QUserSettlement userSettlement;

    public final QWalletTransaction walletTransaction;

    public QTransfer(String variable) {
        this(Transfer.class, forVariable(variable), INITS);
    }

    public QTransfer(Path<? extends Transfer> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QTransfer(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QTransfer(PathMetadata metadata, PathInits inits) {
        this(Transfer.class, metadata, inits);
    }

    public QTransfer(Class<? extends Transfer> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.userSettlement = inits.isInitialized("userSettlement") ? new com.example.onlyone.domain.settlement.entity.QUserSettlement(forProperty("userSettlement"), inits.get("userSettlement")) : null;
        this.walletTransaction = inits.isInitialized("walletTransaction") ? new QWalletTransaction(forProperty("walletTransaction"), inits.get("walletTransaction")) : null;
    }

}

