package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.settlement.repository.TransferRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.dto.response.UserWalletTransactionDto;
import com.example.onlyone.domain.wallet.dto.response.WalletTransactionResponseDto;
import com.example.onlyone.domain.wallet.entity.*;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final TransferRepository transferRepository;
    private final UserService userService;
    private final ClubRepository clubRepository;

    /* 사용자 정산/거래 내역 목록 조회 */
    public WalletTransactionResponseDto getWalletTransactionList(Filter filter, Pageable pageable) {
        if (filter == null) {
            filter = Filter.ALL; // 기본값 처리
        }
        User user = userService.getCurrentUser();
        Wallet wallet = walletRepository.findByUser(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        Page<WalletTransaction> transactionPageList = switch (filter) {
            case ALL -> walletTransactionRepository.findByWallet(wallet, pageable);
            case CHARGE -> walletTransactionRepository.findByWalletAndType(wallet, WalletTransactionType.CHARGE, pageable);
            case TRANSACTION -> walletTransactionRepository.findByWalletAndTypeNot(wallet, WalletTransactionType.CHARGE, pageable);
            default -> throw new CustomException(ErrorCode.INVALID_FILTER);
        };
        List<UserWalletTransactionDto> dtoList = transactionPageList.getContent().stream()
                .map(tx -> convertToDto(tx, tx.getWalletTransactionType()))
                .toList();
        Page<UserWalletTransactionDto> dtoPage = new PageImpl<>(dtoList, pageable, transactionPageList.getTotalElements());
        return WalletTransactionResponseDto.from(dtoPage);
    }

    @Transactional(readOnly = true)
    public UserWalletTransactionDto convertToDto(WalletTransaction walletTransaction, WalletTransactionType walletTransactionType) {
        if (walletTransactionType == WalletTransactionType.CHARGE) {
            // 충전 거래의 경우
            Payment payment = walletTransaction.getPayment();
            String title = payment.getTotalAmount() + "원";
            return UserWalletTransactionDto.from(walletTransaction, title, null);
        } else {
            // 정산 거래의 경우
            Transfer transfer = walletTransaction.getTransfer();
            Schedule schedule = transfer.getUserSettlement().getSettlement().getSchedule();
            String title = schedule.getClub().getName() + ": " + schedule.getName();
            String mainImage = schedule.getClub().getClubImage();
            return UserWalletTransactionDto.from(walletTransaction, title, mainImage);
        }
    }
}
