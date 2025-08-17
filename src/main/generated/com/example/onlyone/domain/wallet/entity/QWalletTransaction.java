package com.example.onlyone.domain.wallet.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QWalletTransaction is a Querydsl query type for WalletTransaction
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QWalletTransaction extends EntityPathBase<WalletTransaction> {

    private static final long serialVersionUID = -1424443350L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QWalletTransaction walletTransaction = new QWalletTransaction("walletTransaction");

    public final com.example.onlyone.global.QBaseTimeEntity _super = new com.example.onlyone.global.QBaseTimeEntity(this);

    public final NumberPath<Integer> amount = createNumber("amount", Integer.class);

    public final NumberPath<Integer> balance = createNumber("balance", Integer.class);

    //inherited
    public final DateTimePath<java.time.LocalDateTime> createdAt = _super.createdAt;

    //inherited
    public final DateTimePath<java.time.LocalDateTime> modifiedAt = _super.modifiedAt;

    public final com.example.onlyone.domain.payment.entity.QPayment payment;

    public final QWallet targetWallet;

    public final QTransfer transfer;

    public final EnumPath<Type> type = createEnum("type", Type.class);

    public final QWallet wallet;

    public final NumberPath<Long> walletTransactionId = createNumber("walletTransactionId", Long.class);

    public final EnumPath<WalletTransactionStatus> walletTransactionStatus = createEnum("walletTransactionStatus", WalletTransactionStatus.class);

    public QWalletTransaction(String variable) {
        this(WalletTransaction.class, forVariable(variable), INITS);
    }

    public QWalletTransaction(Path<? extends WalletTransaction> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QWalletTransaction(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QWalletTransaction(PathMetadata metadata, PathInits inits) {
        this(WalletTransaction.class, metadata, inits);
    }

    public QWalletTransaction(Class<? extends WalletTransaction> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.payment = inits.isInitialized("payment") ? new com.example.onlyone.domain.payment.entity.QPayment(forProperty("payment"), inits.get("payment")) : null;
        this.targetWallet = inits.isInitialized("targetWallet") ? new QWallet(forProperty("targetWallet"), inits.get("targetWallet")) : null;
        this.transfer = inits.isInitialized("transfer") ? new QTransfer(forProperty("transfer"), inits.get("transfer")) : null;
        this.wallet = inits.isInitialized("wallet") ? new QWallet(forProperty("wallet"), inits.get("wallet")) : null;
    }

}

