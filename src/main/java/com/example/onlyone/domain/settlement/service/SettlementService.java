package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.notification.entity.Type;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private final ApplicationEventPublisher eventPublisher;


    /* 정산 Status를 REQUESTED -> COMPLETED로 스케줄링 (낙관적 락 적용)*/
    @Scheduled(cron = "0 55 17 * * *")
    @Transactional
    public void updateTotalStatusIfAllCompleted() {
        List<Settlement> settlements = settlementRepository.findAllByTotalStatus(TotalStatus.REQUESTED);
        for (Settlement settlement : settlements) {
            long totalCount = userSettlementRepository.countBySettlement(settlement);
            long completedCount = userSettlementRepository.countBySettlementAndSettlementStatus(settlement, SettlementStatus.COMPLETED);
            User leader = userScheduleRepository.findLeaderByScheduleAndScheduleRole(settlement.getSchedule(), ScheduleRole.LEADER)
                    .orElseThrow(() -> new CustomException(ErrorCode.LEADER_NOT_FOUND));
            // 모든 정산이 완료된 경우
            if (totalCount > 0 && totalCount == completedCount) {
                settlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
                settlementRepository.save(settlement);
                settlement.getSchedule().updateStatus(ScheduleStatus.CLOSED);
                // 정산 리더에게 완료 알림
                notificationService.createNotification(leader, Type.SETTLEMENT, new String[]{String.valueOf(settlement.getSum())});
            }
        }
    }

    /* 자동 정산 수행 */
    @Transactional(rollbackFor = Exception.class)
    public void automaticSettlement(Long clubId, Long scheduleId) {
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
        Settlement settlement = settlementRepository.findBySchedule(schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
        // 이미 정산 중인 스케줄 예외 처리
        if (settlement.getTotalStatus() != TotalStatus.HOLDING) {
            throw new CustomException(ErrorCode.ALREADY_SETTLING_SCHEDULE);
        }
        // 정산의 sum 업데이트 (count할 때는 리더 제외)
        int totalAmount = (userCount - 1) * schedule.getCost();
        settlement.updateSum(totalAmount);

        settlement.updateTotalStatus(TotalStatus.REQUESTED);
        schedule.updateStatus(ScheduleStatus.SETTLING);

        User leader = userScheduleRepository.findLeaderByScheduleAndScheduleRole(schedule, ScheduleRole.LEADER)
                .orElseThrow(() -> new CustomException(ErrorCode.LEADER_NOT_FOUND));
        Wallet leaderWallet = walletRepository.findByUserWithoutLock(leader)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Wallet failWallet = null;
        long failUserSettlementId = 0L;

        // 자동 정산 수행
        try {
            // 1. 멱등성 보장 & 진행 선점: REQUESTED → IN_PROGRESS 선점
            if (settlementRepository.markProcessing(settlement.getSettlementId()) != 1) {
                throw new CustomException(ErrorCode.ALREADY_SETTLING_SCHEDULE);
            }
            // 2. 원자적 이체
            List<UserSettlement> targets = userSettlementRepository
                    .findAllBySettlement_SettlementIdAndSettlementStatus(settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE);

            for (UserSettlement userSettlement : targets) {
                Wallet memberWallet = walletRepository.findByUserWithoutLock(userSettlement.getUser())
                        .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
                failWallet = memberWallet;
                failUserSettlementId = userSettlement.getUserSettlementId();
                // 2-1) 홀드 캡처 (balance -= amt, hold -= amt) : 0행이면 비정상 → 예외
                int captured = walletRepository.captureHold(userSettlement.getUser().getUserId(), schedule.getCost());
                if (captured != 1) {
                    throw new CustomException(ErrorCode.WALLET_HOLD_CAPTURE_FAILED);
                }
                // 2-2) 리더 가산
                int credited = walletRepository.creditByUserId(leader.getUserId(), schedule.getCost());
                if (credited != 1) {
                    throw new CustomException(ErrorCode.WALLET_CREDIT_APPLY_FAILED);
                }
                // 2-3) 트랜잭션 기록 (멱등키: settlementId-userId)
                walletService.createSuccessfulWalletTransactions(
                        memberWallet.getWalletId(), leaderWallet.getWalletId(),
                        schedule.getCost(), userSettlement);

                // 2-4) 상태 변경
                userSettlement.updateSettlement(SettlementStatus.COMPLETED, LocalDateTime.now());
                userSettlementRepository.save(userSettlement);

                // 2-5) 알림
//                notificationService.createNotification(
//                        userSettlement.getUser(),
//                        Type.SETTLEMENT,
//                        new String[]{String.valueOf(schedule.getCost())});
            }
            // 3. 모두 성공한 경우
            Schedule completedSchedule = scheduleRepository.findById(scheduleId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            Settlement completedSettlement = settlementRepository.findBySchedule(completedSchedule)
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            completedSettlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
            completedSchedule.updateStatus(ScheduleStatus.CLOSED);
//            notificationService.createNotification(
//                    user,
//                    Type.SETTLEMENT,
//                    new String[]{String.valueOf(settlement.getSum())});
        // 4. 예외를 잡아 별도 실패 기록
        } catch (CustomException e) {
            settlement.updateTotalStatus(TotalStatus.REQUESTED);
            registerFailureLogAfterRollback(failWallet.getWalletId(), leaderWallet.getWalletId(), schedule.getCost(), failUserSettlementId, failWallet.getPostedBalance(), leaderWallet.getPostedBalance());
            throw e;
        } catch (Exception e) {
            settlement.updateTotalStatus(TotalStatus.REQUESTED);
            registerFailureLogAfterRollback(failWallet.getWalletId(), leaderWallet.getWalletId(), schedule.getCost(), failUserSettlementId, failWallet.getPostedBalance(), leaderWallet.getPostedBalance());
            throw new CustomException(ErrorCode.SETTLEMENT_PROCESS_FAILED);
        }
    }

    /* 정산 요청 생성 */
    @Deprecated
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
    @Deprecated
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
            if (wallet.getPostedBalance() < amount) {
                throw new CustomException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
            }
            // 1. 잔액 변경 전 상태 저장
            int beforeBalance = wallet.getPostedBalance();
            int leaderBeforeBalance = leaderWallet.getPostedBalance();
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
                    Type.SETTLEMENT,
                    new String[]{String.valueOf(amount)});
        } catch (CustomException e) {
            registerFailureLogAfterRollback(wallet.getWalletId(), leaderWallet.getWalletId(), amount, userSettlement.getUserSettlementId(), wallet.getPostedBalance(), leaderWallet.getPostedBalance());
            throw e;
        } catch (Exception e) {
            registerFailureLogAfterRollback(wallet.getWalletId(), leaderWallet.getWalletId(), amount, userSettlement.getUserSettlementId(), wallet.getPostedBalance(), leaderWallet.getPostedBalance());
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
