package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
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
import com.example.onlyone.domain.wallet.service.WalletService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK;

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
    private final NotificationService notificationService;
    private final WalletService walletService;


    /* 정산 Status를 REQUESTED -> COMPLETED로 스케줄링 (낙관적 락 적용)*/
    @Scheduled(cron = "0 55 17 * * *")
    @Transactional
    public void updateTotalStatusIfAllCompleted() {
        log.info("스케쥴링 진입이요");
        List<Settlement> settlements = settlementRepository.findAllByTotalStatus(TotalStatus.REQUESTED);
        for (Settlement settlement : settlements) {
            long totalCount = userSettlementRepository.countBySettlement(settlement);
            long completedCount = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.COMPLETED);
            User leader = userScheduleRepository.findLeaderByScheduleAndScheduleRole(settlement.getSchedule(), ScheduleRole.LEADER)
                    .orElseThrow(() -> new CustomException(ErrorCode.LEADER_NOT_FOUND));
            log.info("totalCount: {}", totalCount);
            log.info("completedCount: {}", completedCount);
            log.info("schedule: {}", settlement.getSchedule());
            // 모든 정산이 완료된 경우
            if (totalCount > 0 && totalCount == completedCount) {
                settlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
                settlementRepository.save(settlement);
                settlement.getSchedule().updateStatus(ScheduleStatus.CLOSED);
                // 정산 리더에게 완료 알림
                notificationService.createNotification(leader, com.example.onlyone.domain.notification.entity.Type.SETTLEMENT, new String[]{String.valueOf(settlement.getSum())});
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
        int totalAmount = (userCount - 1) * schedule.getCost();
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
    }

    /* 참여자의 정산 수행 */
    @Transactional(rollbackFor = Exception.class)
    public void updateUserSettlement(Long clubId, Long scheduleId) {
        User user = userService.getCurrentUser();
        // 검증 로직
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        UserSettlement userSettlement = userSettlementRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SETTLEMENT_NOT_FOUND));
        userScheduleRepository.findByUserAndSchedule(user, schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_SCHEDULE_NOT_FOUND));
        if (userSettlement.getSettlement().getTotalStatus() == TotalStatus.COMPLETED ||
                userSettlement.getSettlementStatus() == SettlementStatus.COMPLETED) {
            throw new CustomException(ErrorCode.ALREADY_SETTLED_USER);
        }
        User leader = userScheduleRepository.findLeaderByScheduleAndScheduleRole(schedule, ScheduleRole.LEADER)
                .orElseThrow(() -> new CustomException(ErrorCode.LEADER_NOT_FOUND));
        // 비관적 락으로 Wallet 조회
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
            // 1. 잔액 변경 전 상태 저장
            int beforeBalance = wallet.getBalance();
            int leaderBeforeBalance = leaderWallet.getBalance();
            // 2. 실제 잔액 변경
            wallet.updateBalance(beforeBalance - amount);
            leaderWallet.updateBalance(leaderBeforeBalance + amount);
            // 3. 변경된 잔액으로 WalletTransaction 생성
            walletService.createSuccessfulWalletTransactions(
                    wallet.getWalletId(), leaderWallet.getWalletId(), amount,
                    userSettlement
            );
            // 4. UserSettlement 상태 변경
            userSettlement.updateSettlement(SettlementStatus.COMPLETED, LocalDateTime.now()); // PENDING -> COMPLETED
            // 5. 모든 변경사항 저장
            walletRepository.save(wallet);
            walletRepository.save(leaderWallet);
            userSettlementRepository.save(userSettlement);
            // 6. 알림
            notificationService.createNotification(user,
                    com.example.onlyone.domain.notification.entity.Type.SETTLEMENT,
                    new String[]{String.valueOf(amount)});
        } catch (CustomException e) {
            registerFailureLogAfterRollback(wallet.getWalletId(), leaderWallet.getWalletId(), amount, userSettlement.getUserSettlementId(), wallet.getBalance(), leaderWallet.getBalance());
            throw e;
        } catch (Exception e) {
            registerFailureLogAfterRollback(wallet.getWalletId(), leaderWallet.getWalletId(), amount, userSettlement.getUserSettlementId(), wallet.getBalance(), leaderWallet.getBalance());
            throw new CustomException(ErrorCode.SETTLEMENT_PROCESS_FAILED);
        }
    }

    /* 트랜잭션 롤백 후 실패 로그를 기록하기 위한 메서드*/
    private void registerFailureLogAfterRollback(long wId, long lwId, int amount,
                                                 long usId, int wBal, int lwBal) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    walletService.createFailedWalletTransactions(wId, lwId, amount, usId, wBal, lwBal);
                }
            }
        });
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
