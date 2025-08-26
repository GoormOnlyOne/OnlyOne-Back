package com.example.onlyone.domain.wallet.service;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.payment.entity.Method;
import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.payment.entity.Status;
import com.example.onlyone.domain.payment.repository.PaymentRepository;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.TransferRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.dto.response.WalletTransactionResponseDto;
import com.example.onlyone.domain.wallet.entity.*;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@DataJpaTest
@Import({WalletService.class})
public class WalletServiceTest {

    @Autowired
    WalletService walletService;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    WalletTransactionRepository walletTransactionRepository;
    @Autowired
    UserRepository userRepository;
    @MockBean
    UserService userService;

    private User user;
    private Wallet wallet;
    private User another;
    private Wallet targetWallet;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private ClubRepository clubRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private UserSettlementRepository userSettlementRepository;
    @Autowired
    private InterestRepository interestRepository;

    @BeforeEach
    void setUp() {
        user = userRepository.findById(1L).orElseThrow();
        another = userRepository.findById(2L).orElseThrow();
        wallet = walletRepository.findByUser(user).orElseThrow();
        targetWallet = walletRepository.findByUser(another).orElseThrow();
        walletRepository.saveAndFlush(wallet);
        when(userService.getCurrentUser()).thenReturn(user);

        WalletTransaction tx1 = WalletTransaction.builder()
                .wallet(wallet)
                .targetWallet(targetWallet)
                .type(Type.CHARGE)
                .amount(5000)
                .balance(15000)
                .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
                .build();

        WalletTransaction tx2 = WalletTransaction.builder()
                .wallet(wallet)
                .targetWallet(targetWallet)
                .type(Type.OUTGOING)
                .amount(10000)
                .balance(12000)
                .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
                .build();

        // 충전
        Payment payment = Payment.builder()
                .walletTransaction(tx1)
                .tossOrderId("MC4zMzQyODE5OTcwNDQ0")
                .tossPaymentKey("tgen_202508251644127uk99")
                .method(Method.ACCOUNT_TRANSFER)
                .status(Status.DONE)
                .totalAmount(5000L)
                .build();
        paymentRepository.save(payment);
        tx1.updatePayment(payment);
        walletTransactionRepository.save(tx1);

        // 정산
        Interest interest = Interest.builder()
                .category(Category.CRAFT)
                .build();
        interestRepository.save(interest);
        Club club = Club.builder()
                .name("이건 첫 번째 모임")
                .clubImage("image.png")
                .city("서울특별시")
                .district("강남구")
                .interest(interest)
                .description("첫 번째 모임 설명")
                .userLimit(10)
                .build();
        clubRepository.save(club);
        Schedule schedule = Schedule.builder()
                .club(club)
                .name("정산 테스트 스케줄")
                .cost(10000)
                .location("구름스퀘어 강남")
                .scheduleStatus(ScheduleStatus.CLOSED)
                .userLimit(10)
                .scheduleTime(LocalDateTime.now())
                .build();
        scheduleRepository.save(schedule);
        Settlement settlement = Settlement.builder()
                .schedule(schedule)
                .totalStatus(TotalStatus.COMPLETED)
                .sum(10000)
                .receiver(another)
                .build();
        settlementRepository.save(settlement);
        UserSettlement userSettlement = UserSettlement.builder()
                .settlement(settlement)
                .user(user)
                .settlementStatus(SettlementStatus.COMPLETED)
                .build();
        userSettlementRepository.save(userSettlement);
        Transfer transfer = Transfer.builder()
                .walletTransaction(tx2)
                .userSettlement(userSettlement)
                .build();
        transferRepository.save(transfer);
        tx2.updateTransfer(transfer);
        walletTransactionRepository.saveAll(List.of(tx1, tx2));
    }

    @Test
    void 사용자의_정산과_결제_내역_목록이_필터_ALL에_따라_정상_조회된다() {
        Pageable pageable = PageRequest.of(0, 10);

        // when
        WalletTransactionResponseDto response =
                walletService.getWalletTransactionList(Filter.ALL, pageable);

        // then
        assertThat(response.getTotalElement()).isEqualTo(2);
        assertThat(response.getUserWalletTransactionList().get(0).getAmount()).isEqualTo(5000);
        assertThat(response.getUserWalletTransactionList().get(1).getAmount()).isEqualTo(10000);
    }

    @Test
    void 사용자의_정산과_결제_내역_목록이_필터_CHARGE에_따라_정상_조회된다() {
        Pageable pageable = PageRequest.of(0, 10);

        // when
        WalletTransactionResponseDto response =
                walletService.getWalletTransactionList(Filter.CHARGE, pageable);

        // then
        assertThat(response.getTotalElement()).isEqualTo(1);
        assertThat(response.getUserWalletTransactionList().get(0).getAmount()).isEqualTo(5000);
    }

    @Test
    void 사용자의_정산과_결제_내역_목록이_필터_TRANSACTION에_따라_정상_조회된다() {
        Pageable pageable = PageRequest.of(0, 10);

        // when
        WalletTransactionResponseDto response =
                walletService.getWalletTransactionList(Filter.TRANSACTION, pageable);

        // then
        assertThat(response.getTotalElement()).isEqualTo(1);
        assertThat(response.getUserWalletTransactionList().get(0).getAmount()).isEqualTo(10000);
    }


}
