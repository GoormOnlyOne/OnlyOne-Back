package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.*;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.TransferRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.*;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementService {
    private final UserService userService;
    private final ClubRepository clubRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final TransferRepository transferRepository;


    /* 정산 Status를 REQUESTED -> COMPLETED로 스케줄링 (낙관적 락 적용)*/
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void updateTotalStatusIfAllCompleted() {
        List<Settlement> settlements = settlementRepository.findAllByTotalStatus(TotalStatus.REQUESTED);
        for (Settlement settlement : settlements) {
            long totalCount = userSettlementRepository.countBySettlement(settlement);
            long completedCount = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.COMPLETED);
            if (totalCount > 0 && totalCount == completedCount) {
                settlement.updateStatus(TotalStatus.COMPLETED);
                settlementRepository.save(settlement);
            }
        }
    }

    /* 정산 요청 생성 */
    public void createSettlement(Long clubId, Long scheduleId) {
        User user = userService.getCurrentUser();
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        // 종료된 스케줄인지 확인
        if (!(schedule.getScheduleStatus() == ScheduleStatus.ENDED || schedule.getScheduleTime().isBefore(LocalDateTime.now()))) {
            throw new CustomException(ErrorCode.BEFORE_SCHEDULE_START);
        }

        UserSchedule leaderUserSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        // 리더가 호출하고 있는지 확인
        if (leaderUserSchedule.getScheduleRole() != ScheduleRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_CREATE_SETTLEMENT);
        }
        int userCount = userScheduleRepository.countBySchedule(schedule);
        // 비용이 0원이거나 참여자가 1명(리더만)인 경우 → 바로 CLOSED 처리 후 리턴
        if (schedule.getCost() == 0 || userCount <= 1) {
            schedule.updateStatus(ScheduleStatus.CLOSED);
            return;
        }
        schedule.updateStatus(ScheduleStatus.SETTLING);
        int totalAmount = userCount * schedule.getCost();
        Settlement settlement = Settlement.builder()
                .schedule(schedule)
                .sum(totalAmount)
                .totalStatus(TotalStatus.REQUESTED)
                .receiver(user)
                .build();
        settlementRepository.save(settlement);
        List<UserSchedule> userSchedules = userScheduleRepository.findUserSchedulesBySchedule(schedule);
        userSchedules.remove(leaderUserSchedule);
        List<UserSettlement> userSettlements = userSchedules.stream()
                .map(userSchedule -> UserSettlement.builder()
                        .user(userSchedule.getUser())
                        .settlement(settlement)
                        .settlementStatus(SettlementStatus.REQUESTED)
                        .build())
                .toList();
        userSettlementRepository.saveAll(userSettlements);
        // [TODO] Notification 생성 및 유저에게 알림 전송
    }

    public void updateUserSettlement(Long clubId, Long scheduleId) {
        User user = userService.getCurrentUser();
//        User user = userService.getCurrentUser();
        // 유효성 검증
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        UserSettlement userSettlement = userSettlementRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SETTLEMENT_NOT_FOUND));
        userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        if (userSettlement.getSettlement().getTotalStatus() != TotalStatus.REQUESTED ||
                userSettlement.getSettlementStatus() != SettlementStatus.REQUESTED) {
            throw new CustomException(ErrorCode.ALREADY_SETTLED_USER);
        }
        User leader = userScheduleRepository.findLeaderByScheduleAndScheduleRole(schedule, ScheduleRole.LEADER)
                .orElseThrow(() -> new CustomException(ErrorCode.LEADER_NOT_FOUND));
        // Wallet 조회 (비관적 락 적용)
        Wallet wallet = walletRepository.findByUser(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        Wallet leaderWallet = walletRepository.findByUser(leader)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        int amount = schedule.getCost();
        try {
            // 잔액 부족 확인
            if (wallet.getBalance() < amount) {
                throw new CustomException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
            }
            // 포인트 이동
            wallet.updateBalance(wallet.getBalance() - amount);
            leaderWallet.updateBalance(leaderWallet.getBalance() + amount);
            // 리더-멤버의 WalletTransaction, Transfer 기록
            saveWalletTransactions(wallet, leaderWallet, amount, WalletTransactionStatus.COMPLETED, userSettlement);
            // 정산 상태 변경
            userSettlement.updateSettlement(SettlementStatus.COMPLETED, LocalDateTime.now());
            userSettlementRepository.save(userSettlement);
        } catch (Exception e) {
            // 리더-멤버의 WalletTransaction 기록
            saveWalletTransactions(wallet, leaderWallet, amount, WalletTransactionStatus.FAILED, userSettlement);
            throw e;
        }
        // [TODO] Notification 생성 및 유저에게 알림 전송
    }

    /* WalletTransaction 저장 로직 */
    private void saveWalletTransactions(Wallet wallet, Wallet leaderWallet, int amount,
                                        WalletTransactionStatus status, UserSettlement userSettlement) {
        // WalletTransaction 저장
        WalletTransaction walletTransaction = WalletTransaction.builder()
                .type(Type.OUTGOING)
                .amount(amount)
                .balance(wallet.getBalance())
                .walletTransactionStatus(status)
                .wallet(wallet)
                .targetWallet(leaderWallet)
                .build();
        walletTransactionRepository.save(walletTransaction);
        WalletTransaction leaderWalletTransaction = WalletTransaction.builder()
                .type(Type.INCOMING)
                .amount(amount)
                .balance(leaderWallet.getBalance())
                .walletTransactionStatus(status)
                .wallet(leaderWallet)
                .targetWallet(wallet)
                .build();
        walletTransactionRepository.save(leaderWalletTransaction);
        // Transfer 저장
        Transfer transfer = Transfer.builder()
                .userSettlement(userSettlement)
                .walletTransaction(walletTransaction)
                .build();
        transferRepository.save(transfer);
        walletTransaction.updateTransfer(transfer);
        Transfer leaderTransfer = Transfer.builder()
                .userSettlement(userSettlement)
                .walletTransaction(leaderWalletTransaction)
                .build();
        transferRepository.save(leaderTransfer);
        leaderWalletTransaction.updateTransfer(leaderTransfer);
    }

    /* 스케줄 참여자 정산 목록 조회 */
    @Transactional(readOnly = true)
    public SettlementResponseDto getSettlementList(Long clubId, Long scheduleId, Pageable pageable) {
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        Settlement settlement = settlementRepository.findBySchedule(schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
        Page<UserSettlementDto> userSettlementList = userSettlementRepository
                .findAllDtoBySettlement(settlement, pageable);
        return SettlementResponseDto.from(userSettlementList);
    }


}
