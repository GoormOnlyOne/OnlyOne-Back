package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.payment.entity.Payment;
// TODO: 순환 의존성 방지 - 이벤트 기반으로 변경 필요
// import com.example.onlyone.domain.settlement.entity.SettlementStatus;
// import com.example.onlyone.domain.settlement.entity.UserSettlement;
// import com.example.onlyone.domain.settlement.repository.TransferRepository;
// import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.dto.response.UserWalletTransactionDto;
import com.example.onlyone.domain.wallet.dto.response.WalletTransactionResponseDto;
import com.example.onlyone.domain.wallet.entity.*;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    // TODO: 순환 의존성 방지 - Settlement 도메인과의 의존성 제거
    // private final TransferRepository transferRepository;
    private final UserService userService;
    private final ClubRepository clubRepository;
    // private final UserSettlementRepository userSettlementRepository;

    /* 사용자 정산/거래 내역 목록 조회 */
    public WalletTransactionResponseDto getWalletTransactionList(Filter filter, Pageable pageable) {
        if (filter == null) {
            filter = Filter.ALL; // 기본값 처리
        }
        User user = userService.getCurrentUser();
        Wallet wallet = walletRepository.findByUserWithoutLock(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        Page<WalletTransaction> transactionPageList = switch (filter) {
            case ALL -> walletTransactionRepository.findByWalletAndWalletTransactionStatus(wallet, WalletTransactionStatus.COMPLETED, pageable);
            case CHARGE -> walletTransactionRepository.findByWalletAndTypeAndWalletTransactionStatus(wallet, TransactionType.CHARGE, WalletTransactionStatus.COMPLETED, pageable);
            case TRANSACTION -> walletTransactionRepository.findByWalletAndTypeNotAndWalletTransactionStatus(wallet, TransactionType.CHARGE, WalletTransactionStatus.COMPLETED, pageable);
            default -> throw new CustomException(ErrorCode.INVALID_FILTER);
        };
        List<UserWalletTransactionDto> dtoList = transactionPageList.getContent().stream()
                .map(tx -> convertToDto(tx, tx.getType()))
                .toList();
        Page<UserWalletTransactionDto> dtoPage = new PageImpl<>(dtoList, pageable, transactionPageList.getTotalElements());
        return WalletTransactionResponseDto.from(dtoPage);
    }

    @Transactional(readOnly = true)
    public UserWalletTransactionDto convertToDto(WalletTransaction walletTransaction, TransactionType type) {
        if (type == TransactionType.CHARGE) {
            // 충전 거래의 경우
            Payment payment = walletTransaction.getPayment();
            String title = payment.getTotalAmount() + "원";
            return UserWalletTransactionDto.from(walletTransaction, title, null);
        } else {
            // TODO: 순환 의존성 방지 - 정산 거래는 이벤트 기반으로 변경 필요
            // 정산 거래의 경우
            // Transfer transfer = walletTransaction.getTransfer();
            // Schedule schedule = transfer.getUserSettlement().getSettlement().getSchedule();
            // String title = schedule.getClub().getName() + ": " + schedule.getName();
            // String mainImage = schedule.getClub().getClubImage();
            return UserWalletTransactionDto.from(walletTransaction, "정산 거래", null);
        }
    }

    // TODO: 순환 의존성 방지 - Settlement 도메인과의 의존성 제거, 이벤트 기반으로 변경 필요
    // public void createSuccessfulWalletTransactions(Long walletId, Long leaderWalletId, Long amount,
    //                                                 UserSettlement userSettlement) {
    //     Wallet wallet = walletRepository.findById(walletId).orElseThrow();
    //     Wallet leaderWallet = walletRepository.findById(leaderWalletId).orElseThrow();
    //     // 출금 트랜잭션
    //     WalletTransaction walletTransaction = WalletTransaction.builder()
    //             .type(TransactionType.OUTGOING)
    //             .amount(amount)
    //             .balance(wallet.getPostedBalance())
    //             .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
    //             .wallet(wallet)
    //             .targetWallet(leaderWallet)
    //             .build();
    //     // 입금 트랜잭션
    //     WalletTransaction leaderWalletTransaction = WalletTransaction.builder()
    //             .type(TransactionType.INCOMING)
    //             .amount(amount)
    //             .balance(leaderWallet.getPostedBalance())
    //             .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
    //             .wallet(leaderWallet)
    //             .targetWallet(wallet)
    //             .build();
    //     walletTransactionRepository.save(walletTransaction);
    //     walletTransactionRepository.save(leaderWalletTransaction);
    //     // Transfer 생성 및 연결
    //     createAndSaveTransfers(userSettlement, walletTransaction, leaderWalletTransaction);
    // }
    //
    // public void createAndSaveTransfers(UserSettlement userSettlement, WalletTransaction walletTransaction, WalletTransaction leaderWalletTransaction) {
    //     // Transfer 저장
    //     Transfer transfer = Transfer.builder()
    //             .userSettlement(userSettlement)
    //             .walletTransaction(walletTransaction)
    //             .build();
    //     transferRepository.save(transfer);
    //     walletTransaction.updateTransfer(transfer);
    //     Transfer leaderTransfer = Transfer.builder()
    //             .userSettlement(userSettlement)
    //             .walletTransaction(leaderWalletTransaction)
    //             .build();
    //     transferRepository.save(leaderTransfer);
    //     leaderWalletTransaction.updateTransfer(leaderTransfer);
    // }
    //
    //
    //
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    // public void createFailedWalletTransactions(Long walletId, Long leaderWalletId, Long amount,
    //                                               Long userSettlementId, Long walletBalance, Long leaderWalletBalance) {
    //     Wallet wallet = walletRepository.getReferenceById(walletId);
    //     Wallet leaderWallet   = walletRepository.getReferenceById(leaderWalletId);
    //     UserSettlement userSettlement = userSettlementRepository.getReferenceById(userSettlementId);
    //
    //     // 실패한 트랜잭션
    //     WalletTransaction failedOutgoing = WalletTransaction.builder()
    //             .type(TransactionType.OUTGOING)
    //             .amount(amount)
    //             .balance(walletBalance) // 잔액 변경 없음
    //             .walletTransactionStatus(WalletTransactionStatus.FAILED)
    //             .wallet(wallet)
    //             .targetWallet(leaderWallet)
    //             .build();
    //     WalletTransaction failedIncoming = WalletTransaction.builder()
    //             .type(TransactionType.INCOMING)
    //             .amount(amount)
    //             .balance(leaderWalletBalance) // 잔액 변경 없음
    //             .walletTransactionStatus(WalletTransactionStatus.FAILED)
    //             .wallet(leaderWallet)
    //             .targetWallet(wallet)
    //             .build();
    //     walletTransactionRepository.save(failedOutgoing);
    //     walletTransactionRepository.save(failedIncoming);
    //     userSettlementRepository.updateStatusIfRequested(userSettlementId, SettlementStatus.FAILED);
    //     createAndSaveTransfers(userSettlement, failedOutgoing, failedIncoming);
    // }
}
