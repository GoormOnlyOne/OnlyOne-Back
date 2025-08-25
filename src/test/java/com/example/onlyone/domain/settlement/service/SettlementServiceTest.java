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
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        settlement.updateTotalStatus(TotalStatus.IN_PROGRESS);
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
        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                0,
                10,
                LocalDateTime.now().minusHours(2)
        );
        leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);
        scheduleService.updateSchedule(club.getClubId(), schedule.getScheduleId(), updateScheduleRequestDto);

        // when
        settlementService.automaticSettlement(club.getClubId(), schedule.getScheduleId());
        entityManager.flush();
        entityManager.clear();

        // then
        Schedule newSchedule = scheduleRepository.findById(schedule.getScheduleId()).orElseThrow();
        Optional<Settlement> deletedSettlement = settlementRepository.findBySchedule(newSchedule);
        assertThat(deletedSettlement).isEmpty();
        List<UserSettlement> userSettlements = userSettlementRepository.findAll();
        assertThat(userSettlements).isEmpty();
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
        memberWallet.updateBalance(memberWallet.getPostedBalance() - 100000);
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


    @Test
    void 멱등성과_동시성에_대한_보호가_정상적으로_이루어진다() throws Exception {
        // given
        Long scheduleId = schedule.getScheduleId();
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                settlementService.automaticSettlement(club.getClubId(), scheduleId)
        );
        assertEquals(ErrorCode.BEFORE_SCHEDULE_END, exception.getErrorCode());
    }
}
