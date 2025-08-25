package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleCreateResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.schedule.service.ScheduleService;
import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Type;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.domain.wallet.service.WalletService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD;

@ActiveProfiles("test")
@DataJpaTest
@Transactional
@Import({SettlementService.class, WalletService.class, ScheduleService.class, UserService.class, ClubService.class})
public class SettlementServiceTest {

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private WalletService walletService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ClubService clubService;
    @MockBean
    private UserService userService;
    @MockBean
    private NotificationService notificationService;

    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private UserSettlementRepository userSettlementRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private ClubRepository clubRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserScheduleRepository userScheduleRepository;
    @Autowired
    EntityManager entityManager;

    private Club club;
    private Schedule schedule;
    private Settlement settlement;
    private User leader;
    private User member1;
    private User member2;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @BeforeEach
    void setUp() {
        leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);
        club = clubRepository.findById(responseDto.getClubId()).orElse(null);
        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                100,
                10,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto scheduleResponseDto = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        schedule = scheduleRepository.findById(scheduleResponseDto.getScheduleId()).orElse(null);
        settlement = settlementRepository.findBySchedule(schedule).orElse(null);

        member1 = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member1);
        clubService.joinClub(club.getClubId());
        scheduleService.joinSchedule(club.getClubId(), scheduleResponseDto.getScheduleId());

        member2 = userRepository.findById(3L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member2);
        clubService.joinClub(club.getClubId());
        scheduleService.joinSchedule(club.getClubId(), scheduleResponseDto.getScheduleId());

        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                100,
                10,
                LocalDateTime.now().minusHours(2)
        );

        leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);
        scheduleService.updateSchedule(club.getClubId(), scheduleResponseDto.getScheduleId(), updateScheduleRequestDto);
        schedule.updateStatus(ScheduleStatus.ENDED);
        settlement.updateTotalStatus(TotalStatus.HOLDING);
        entityManager.flush();
//        entityManager.clear();
    }

    @Test
    void 종료된_정모에_리더가_정상적으로_자동_정산을_요청한다() {
        // given
        settlement.updateTotalStatus(TotalStatus.HOLDING);
        schedule.updateStatus(ScheduleStatus.ENDED);
        Long scheduleId = schedule.getScheduleId();
        entityManager.flush();

        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // when
        settlementService.automaticSettlement(club.getClubId(), scheduleId);
        entityManager.flush();
        entityManager.clear();

        // then
        Schedule newSchedule = scheduleRepository.findById(scheduleId).orElseThrow();
        Settlement newSettlement = settlementRepository.findBySchedule(newSchedule).orElseThrow();
        List<UserSettlement> userSettlements = userSettlementRepository.findAllBySettlement_SettlementIdAndSettlementStatus(newSettlement.getSettlementId(), SettlementStatus.COMPLETED);

        assertThat(newSchedule.getScheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
        assertThat(newSettlement.getTotalStatus()).isEqualTo(TotalStatus.COMPLETED);
        assertThat(userSettlements).hasSize(2);
    }

    @Test
    void 상태가_ENDED거나_시작_시간이_지난_정모는_정산_요청_가능하다() {
        // given
        settlement.updateTotalStatus(TotalStatus.HOLDING);
        schedule.updateStatus(ScheduleStatus.ENDED);
        Long scheduleId = schedule.getScheduleId();
        entityManager.flush();

        Mockito.when(userService.getCurrentUser()).thenReturn(leader);
        schedule.updateStatus(ScheduleStatus.ENDED);

        // when
        settlementService.automaticSettlement(club.getClubId(), scheduleId);
        entityManager.flush();
        entityManager.clear();

        // then
        Schedule newSchedule = scheduleRepository.findById(scheduleId).orElseThrow();
        Settlement newSettlement = settlementRepository.findBySchedule(newSchedule).orElseThrow();
        List<UserSettlement> userSettlements = userSettlementRepository.findAllBySettlement_SettlementIdAndSettlementStatus(newSettlement.getSettlementId(), SettlementStatus.COMPLETED);

        assertThat(newSchedule.getScheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
        assertThat(newSettlement.getTotalStatus()).isEqualTo(TotalStatus.COMPLETED);
        assertThat(userSettlements).hasSize(2);
    }

    @Test
    void 상태가_ENDND가_아니고_시작_전인_정모에_정산_요청하면_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                100,
                10,
                LocalDateTime.now().plusHours(2)
        );
        leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);
        scheduleService.updateSchedule(club.getClubId(), schedule.getScheduleId(), updateScheduleRequestDto);

        settlement.updateTotalStatus(TotalStatus.HOLDING);
        schedule.updateStatus(ScheduleStatus.READY);
        Long scheduleId = schedule.getScheduleId();
        entityManager.flush();

        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), scheduleId)
        );
        assertEquals(ErrorCode.BEFORE_SCHEDULE_END, exception.getErrorCode());
    }

    @Test
    void 비용이_0원인_경우_정모가_CLOSED된다() {
        // given
        Schedule managed = scheduleRepository.findById(schedule.getScheduleId()).orElseThrow();
        managed.updateStatus(ScheduleStatus.READY);
        managed.update("온리원 첫 번째 정모", "구름스퀘어 강남", 0, 10, LocalDateTime.now().minusHours(2));
        scheduleRepository.saveAndFlush(managed); // or entityManager.flush()
        entityManager.clear();

        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // when
        settlementService.automaticSettlement(club.getClubId(), managed.getScheduleId());
        entityManager.flush();
        entityManager.clear();

        // then
        Schedule newSchedule = scheduleRepository.findById(managed.getScheduleId()).orElseThrow();
        assertThat(settlementRepository.findBySchedule(newSchedule)).isEmpty();
        assertThat(userSettlementRepository.findAll()).isEmpty();
        assertThat(newSchedule.getScheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
    }

    @Test
    void 참여자가_1명인_경우_정모가_CLOSED된다() {
        // given
        ScheduleRequestDto createScheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                10,
                1,
                LocalDateTime.now().minusHours(2)
        );
        leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);
        ScheduleCreateResponseDto responseDto = scheduleService.createSchedule(club.getClubId(), createScheduleRequestDto);

        // when
        settlementService.automaticSettlement(club.getClubId(), responseDto.getScheduleId());
        entityManager.flush();
        entityManager.clear();

        // then
        Schedule newSchedule = scheduleRepository.findById(responseDto.getScheduleId()).orElseThrow();
        Optional<Settlement> newSettlement = settlementRepository.findBySchedule(newSchedule);

        assertThat(newSchedule.getScheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
        assertThat(newSettlement).isEmpty();
    }

    @Test
    void 리더가_아닌_멤버가_정산_요청하면_예외가_발생한다() throws Exception {
        // given
        Mockito.when(userService.getCurrentUser()).thenReturn(member1);

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.MEMBER_CANNOT_CREATE_SETTLEMENT, exception.getErrorCode());
    }

    @Test
    void 이미_진행_중이거나_완료된_정산의_경우_예외가_발생한다() throws Exception {
        // given
        Long scheduleId = schedule.getScheduleId();
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);
        settlement.updateTotalStatus(TotalStatus.COMPLETED);
        schedule.updateStatus(ScheduleStatus.CLOSED);
        schedule.update("온리원 첫 번째 정모", "구름스퀘어 강남", 100, 10, LocalDateTime.now().plusHours(2));
        scheduleRepository.saveAndFlush(schedule);
        entityManager.flush();
        entityManager.clear();

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), scheduleId)
        );
        assertEquals(ErrorCode.BEFORE_SCHEDULE_END, exception.getErrorCode());
    }

    @Test
    void 자동_정산_중_참여자_잔액이_부족하면_예외가_발생한다() {
        // given
        Long scheduleId = schedule.getScheduleId();
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // 잔액 부족 상황
        Wallet memberWallet = walletRepository.findByUserWithoutLock(member1)
                .orElseThrow();
        memberWallet.updateBalance(memberWallet.getPostedBalance() - 100000);
        walletRepository.saveAndFlush(memberWallet);
        entityManager.flush();
        entityManager.clear();

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), scheduleId)
        );
        assertEquals(ErrorCode.WALLET_HOLD_CAPTURE_FAILED, exception.getErrorCode());
    }

    @Test
    void 자동_정산_중_예외가_발생하면_전체_롤백하며_Settlement_상태를_FAILED로_변경한다() {
        // given
        Long scheduleId = schedule.getScheduleId();
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // 잔액 부족 상황
        Wallet memberWallet = walletRepository.findByUserWithoutLock(member1)
                .orElseThrow();
        memberWallet.updateBalance(memberWallet.getPostedBalance() - 1000000);
        walletRepository.saveAndFlush(memberWallet);
        entityManager.flush();
        entityManager.clear();

        // when
        assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), scheduleId)
        );

        // then
        Settlement failedSettlement = settlementRepository.findBySchedule(schedule)
                .orElseThrow();
        assertThat(failedSettlement.getTotalStatus()).isEqualTo(TotalStatus.FAILED);
    }

    /* 트랜잭션 롤백 후 실패 로그를 기록*/
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void 자동_정산_중_예외_시_실패로그가_저장된다() {
        // given
        Long scheduleId = schedule.getScheduleId();
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // 잔액 부족 상황
        Wallet memberWallet = walletRepository.findByUserWithoutLock(member1)
                .orElseThrow();
        memberWallet.updateBalance(memberWallet.getPostedBalance() - 100000);
        walletRepository.saveAndFlush(memberWallet);
        entityManager.flush();
        entityManager.clear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), scheduleId)
        );
        TestTransaction.flagForRollback();
        TestTransaction.end();

        // then

        TestTransaction.start();
        Pageable pageable = PageRequest.of(0, 10);
        Page<WalletTransaction> failed = walletTransactionRepository
                .findByWalletAndTypeAndWalletTransactionStatus(
                        walletRepository.findByUserWithoutLock(member1).orElseThrow(),
                        Type.OUTGOING,
                        WalletTransactionStatus.FAILED,
                        pageable
                );
        assertThat(failed.hasContent()).isTrue();
    }

    /* 정모 참여자 정산 조회 */
    @Test
    void 스케줄_참여자_정산_목록을_페이징으로_조회한다() {
        // when
        Pageable pageable = PageRequest.of(0, 10);
        SettlementResponseDto result =
                settlementService.getSettlementList(club.getClubId(), schedule.getScheduleId(), pageable);

        // then
        // 페이지 메타는 구현체에 따라 다를 수 있지만, 일반적으로 content/total(또는 size) 확인
        assertThat(result).isNotNull();
        assertThat(result.getUserSettlementList()).hasSize(2); // 멤버 2명
        assertThat(result.getUserSettlementList())
                .extracting("nickname")
                .containsExactlyInAnyOrder("Bob", "Charlie");
        assertThat(result.getCurrentPage()).isEqualTo(0);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getTotalElement()).isEqualTo(2);
    }

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @ParameterizedTest
    @ValueSource(ints = {2, 5, 10})
    void 멱등성과_동시성에_대한_보호가_정상적으로_이루어진다(int threads) throws Exception {
        // given
        Long clubId = club.getClubId();
        Long scheduleId = schedule.getScheduleId();

        // 동시 테스트 전, 현재 트랜잭션 커밋 (다른 스레드에서 볼 수 있게 설정)
        // @DataJpaTest 환경에서는 테스트 메서드마다 트랜잭션이 자동으로 열려있기 때문
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // 동시 실행 환경 세팅
        ExecutorService pool = Executors.newFixedThreadPool(threads); // 스레드 동시 실행
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);

        AtomicInteger success = new AtomicInteger(0); // 성공한 스레드 수 카운트
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // 동시 요청 작업 처리: 모든 스레드는 동시에 automaticSettlement 호출을 시도
        Runnable task = () -> {
            try {
                startGate.await();
                Mockito.when(userService.getCurrentUser()).thenReturn(leader);
                settlementService.automaticSettlement(clubId, scheduleId);
                success.incrementAndGet();
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneGate.countDown();
            }
        };
        for (int i = 0; i < threads; i++) pool.submit(task);

        // when
        startGate.countDown();
        doneGate.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // 검증 위해 트랜잭션 재시작
        TestTransaction.start();

        // then

        // 정확히 하나만 성공
        assertThat(success.get()).isEqualTo(1);
        // 나머지는 모두 선점 실패(ALREADY_SETTLING_SCHEDULE)
        long already = errors.stream()
                .filter(e -> e instanceof CustomException ce
                        && ce.getErrorCode() == ErrorCode.ALREADY_SETTLING_SCHEDULE)
                .count();
        assertThat(already).isEqualTo(threads - 1);

        long otherErrors = errors.size() - already;
        assertThat(otherErrors).isEqualTo(0);

        // DB의 최종 상태 검증
        entityManager.clear();
        Schedule checkedSchedule = scheduleRepository.findById(scheduleId).orElseThrow();
        Settlement checkedSettlement = settlementRepository.findBySchedule(checkedSchedule).orElseThrow();
        assertThat(checkedSchedule.getScheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
        assertThat(checkedSettlement.getTotalStatus()).isEqualTo(TotalStatus.COMPLETED);

        // 완료 후 재호출은 멱등 방어
        CustomException second = assertThrows(CustomException.class, () -> {
            Mockito.when(userService.getCurrentUser()).thenReturn(leader);
            settlementService.automaticSettlement(clubId, scheduleId);
        });
        assertThat(second.getErrorCode()).isEqualTo(ErrorCode.ALREADY_SETTLING_SCHEDULE);
    }

}
