package com.example.onlyone.domain.payment.service;

import com.example.onlyone.domain.payment.dto.request.ConfirmTossPayRequest;
import com.example.onlyone.domain.payment.dto.response.ConfirmTossPayResponse;
import com.example.onlyone.domain.payment.entity.Method;
import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.payment.entity.Status;
import com.example.onlyone.domain.payment.repository.PaymentRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Type;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.feign.TossPaymentClient;
import com.example.onlyone.support.TestRedisConfig;
import com.example.onlyone.support.TestRedisContainerConfig;
import feign.FeignException;
import feign.Request;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@DataJpaTest
@Import({PaymentService.class, TestRedisConfig.class})
class PaymentServiceTest extends TestRedisContainerConfig {

    @Autowired PaymentService paymentService;

    @Autowired PaymentRepository paymentRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedisTemplate<String, Object> redisTemplate;
    @Autowired EntityManager entityManager;

    @MockitoBean TossPaymentClient tossPaymentClient;
    @MockitoBean UserService userService;
    @Autowired
    PlatformTransactionManager txManager;

    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        user = userRepository.findById(1L).orElseThrow();
        wallet = walletRepository.findByUser(user).orElseThrow();
        wallet.updateBalance(0);
        walletRepository.saveAndFlush(wallet);

        when(userService.getCurrentUser()).thenReturn(user);
    }

    private static String generateOrderId() {
        String base = Double.toString(Math.random());
        return Base64.getEncoder().encodeToString(base.getBytes(StandardCharsets.UTF_8)).substring(0, 20);
    }

    private static String generatePaymentKey() {
        String time = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String rand = UUID.randomUUID().toString().substring(0, 5);
        return "tgen_" + time + rand;
    }

    /* 세션에 결제 정보 임시 저장 */
    @Test
    void Redis에_결제_정보를_임시_저장한다() {
        // given
        var orderId = "order-redis-ok";
        long amount = 10_000L;

        var dto = Mockito.mock(com.example.onlyone.domain.payment.dto.request.SavePaymentRequestDto.class);
        when(dto.getOrderId()).thenReturn(orderId);
        when(dto.getAmount()).thenReturn(amount);

        // when
        paymentService.savePaymentInfo(dto, null);

        // then
        Long ttl = redisTemplate.getExpire("payment:" + orderId);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0);

        // when: confirmPayment로 검증 및 삭제
        paymentService.confirmPayment(dto, null);

        // then: 삭제 확인(getExpire == -2 : 키 없음)
        Long after = redisTemplate.getExpire("payment:" + orderId);
        assertThat(after).isEqualTo(-2);
    }

    /* 세션에 저장한 결제 정보와 일치 여부 확인 */
    @Test
    void Redis에_결제_정보가_저장되어_있지_않으면_예외가_발생한다() {
        // when
        var orderId = generateOrderId();
        var dto = Mockito.mock(com.example.onlyone.domain.payment.dto.request.SavePaymentRequestDto.class);
        when(dto.getOrderId()).thenReturn(orderId);
        when(dto.getAmount()).thenReturn(1000L);

        // then
        assertThatThrownBy(() -> paymentService.confirmPayment(dto, null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAYMENT_INFO);
    }

    /* 세션에 저장한 결제 정보와 일치 여부 확인 */
    @Test
    void Redis에_저장된_결제_정보와_일치하지_않으면_예외가_발생한다() {
        // given
        var orderId = generateOrderId();
        redisTemplate.opsForValue().set("payment:" + orderId, "3000");

        // when
        var dto = Mockito.mock(com.example.onlyone.domain.payment.dto.request.SavePaymentRequestDto.class);
        when(dto.getOrderId()).thenReturn(orderId);
        when(dto.getAmount()).thenReturn(1000L);

        // then
        assertThatThrownBy(() -> paymentService.confirmPayment(dto, null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAYMENT_INFO);
    }

    /* 토스페이먼츠 결제 승인 */
    @Test
    void 토스페이먼츠_결제가_정상적으로_승인된다() {
        // given
        var orderId = generateOrderId();
        long amount = 15_000L;
        var paymentKey = generatePaymentKey();

        var req = mock(ConfirmTossPayRequest.class);
        when(req.getOrderId()).thenReturn(orderId);
        when(req.getAmount()).thenReturn(amount);
        when(req.getPaymentKey()).thenReturn(paymentKey);

        var resp = mock(ConfirmTossPayResponse.class);
        when(resp.getPaymentKey()).thenReturn(paymentKey);
        when(resp.getStatus()).thenReturn("DONE");
        when(resp.getMethod()).thenReturn("CARD");

        when(tossPaymentClient.confirmPayment(req)).thenReturn(resp);

        // when
        paymentService.confirm(req);

        // then
        Wallet refreshed = walletRepository.findByUser(user).orElseThrow();
        assertThat(refreshed.getPostedBalance()).isEqualTo(15_000);

        Payment payment = paymentRepository.findByTossOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Status.DONE);
        assertThat(payment.getMethod()).isEqualTo(Method.CARD);
        assertThat(payment.getTossPaymentKey()).isEqualTo(paymentKey);
    }

    @Test
    void 이미_완료된_결제에_대해_승인_요청을_보내면_예외가_발생한다() {
        // given
        var orderId = generateOrderId();
        var paymentKey = generatePaymentKey();
        long amount = 5_000L;

        // when
        var req = mock(ConfirmTossPayRequest.class);
        when(req.getOrderId()).thenReturn(orderId);
        when(req.getAmount()).thenReturn(amount);
        when(req.getPaymentKey()).thenReturn(paymentKey);

        var resp = mock(ConfirmTossPayResponse.class);
        when(resp.getPaymentKey()).thenReturn(paymentKey);
        when(resp.getStatus()).thenReturn("DONE");
        when(resp.getMethod()).thenReturn("CARD");
        when(tossPaymentClient.confirmPayment(req)).thenReturn(resp);

        paymentService.confirm(req);

        // then
        assertThatThrownBy(() -> paymentService.confirm(req))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_COMPLETED_PAYMENT);
    }

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @ParameterizedTest
    @ValueSource(ints = {2, 5, 10})
    void 멱등성과_동시성에_대한_보호가_정상적으로_이루어진다(int threads) throws Exception {
        var orderId    = generateOrderId();
        var paymentKey = generatePaymentKey();
        long amount    = 7_000L;

        when(userService.getCurrentUser()).thenReturn(user);
        paymentRepository.saveAndFlush(
                Payment.builder()
                        .tossOrderId(orderId)
                        .status(Status.READY)
                        .totalAmount(amount)
                        .build()
        );

        // PG 모킹
        var req = mock(ConfirmTossPayRequest.class);
        when(req.getOrderId()).thenReturn(orderId);
        when(req.getAmount()).thenReturn(amount);
        when(req.getPaymentKey()).thenReturn(paymentKey);

        var resp = mock(ConfirmTossPayResponse.class);
        when(resp.getPaymentKey()).thenReturn(paymentKey);
        when(resp.getStatus()).thenReturn("DONE");
        when(resp.getMethod()).thenReturn("CARD");
        when(tossPaymentClient.confirmPayment(any(ConfirmTossPayRequest.class))).thenAnswer(inv -> {
            Thread.sleep(10);
            return resp;
        });

        // 픽스처 커밋
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // 1) 동시 실행
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate  = new CountDownLatch(threads);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger already = new AtomicInteger(0);
        AtomicInteger progress = new AtomicInteger(0);
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                startGate.await();
                new TransactionTemplate(txManager).execute(status -> {
                    paymentService.confirm(req);
                    success.incrementAndGet();
                    return null;
                });
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.ALREADY_COMPLETED_PAYMENT) {
                    already.incrementAndGet();
                } else if (e.getErrorCode() == ErrorCode.PAYMENT_IN_PROGRESS) {
                    progress.incrementAndGet();
                } else {
                    unexpected.add(e);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                doneGate.countDown();
            }
        };

        for (int i = 0; i < threads; i++) pool.submit(task);

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 2) 검증(새 트랜잭션)
        TestTransaction.start();
        try {
            entityManager.clear();

            Map<String, Long> summary = unexpected.stream().collect(
                    java.util.stream.Collectors.groupingBy(
                            e -> (e instanceof CustomException ce)
                                    ? "CustomException:" + ce.getErrorCode()
                                    : e.getClass().getSimpleName(),
                            java.util.stream.Collectors.counting()
                    )
            );

            assertThat(success.get())
                    .isEqualTo(1);

            int concurrencyFailures = already.get() + progress.get();
            assertThat(concurrencyFailures)
                    .isEqualTo(threads - 1);

            // 선점 가드가 PG 이전에 동작한다면 1회 호출이어야 함
            verify(tossPaymentClient, times(1)).confirmPayment(any(ConfirmTossPayRequest.class));

            Payment p = paymentRepository.findByTossOrderId(orderId).orElseThrow();
            assertThat(p.getStatus()).isEqualTo(Status.DONE);
            assertThat(p.getTossPaymentKey()).isEqualTo(paymentKey);

            Wallet w2 = walletRepository.findByUser(user).orElseThrow();
            assertThat(w2.getPostedBalance()).isEqualTo(amount);

            CustomException second = assertThrows(CustomException.class, () -> paymentService.confirm(req));
            assertThat(second.getErrorCode()).isEqualTo(ErrorCode.ALREADY_COMPLETED_PAYMENT);
        } finally {
            TestTransaction.end();
        }
    }

    @Test
    void 결제_승인_중_TossPayment에서_400응답이_발생하면_예외가_발생한다() {
        // given
        var orderId = generateOrderId();
        long amount = 1000L;

        // when
        var req = mock(ConfirmTossPayRequest.class);
        when(req.getOrderId()).thenReturn(orderId);
        when(req.getAmount()).thenReturn(amount);

        Request request = Request.create(Request.HttpMethod.POST, "/confirm",
                Collections.emptyMap(), null, UTF_8, null);
        FeignException ex = new FeignException.BadRequest("bad", request, null, null);

        when(tossPaymentClient.confirmPayment(req)).thenThrow(ex);

        // then
        assertThatThrownBy(() -> paymentService.confirm(req))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAYMENT_INFO);
    }

    @Test
    void 결제_승인_중_TossPayment_서버_오류가_발생하면_예외가_발생한다() {
        // given
        var orderId = generateOrderId();
        long amount = 1000L;

        // when
        var req = mock(ConfirmTossPayRequest.class);
        when(req.getOrderId()).thenReturn(orderId);
        when(req.getAmount()).thenReturn(amount);

        FeignException ex = new FeignException.InternalServerError(
                "boom",
                Request.create(Request.HttpMethod.POST, "/confirm",
                        Collections.emptyMap(), null, StandardCharsets.UTF_8, null),
                null, null);
        when(tossPaymentClient.confirmPayment(req)).thenThrow(ex);

        // then
        assertThatThrownBy(() -> paymentService.confirm(req))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TOSS_PAYMENT_FAILED);
    }

    @Test
    void 결제_승인_중_예상치_못한_런타임예외가_발생하면_예외가_발생한다() {
        // given
        var orderId = generateOrderId();
        long amount = 1000L;

        // when
        var req = mock(ConfirmTossPayRequest.class);
        when(req.getOrderId()).thenReturn(orderId);
        when(req.getAmount()).thenReturn(amount);

        when(tossPaymentClient.confirmPayment(req)).thenThrow(new RuntimeException("rt"));

        // then
        assertThatThrownBy(() -> paymentService.confirm(req))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    void 결제_실패_후_재시도하면_정상적으로_승인된다() {
        // given
        var orderId = generateOrderId();
        long amount = 3_000L;

        // 1차 실패 (reportFail 호출 → 상태 CANCELED)
        var failReq = mock(ConfirmTossPayRequest.class);
        when(failReq.getOrderId()).thenReturn(orderId);
        when(failReq.getAmount()).thenReturn(amount);
        when(failReq.getPaymentKey()).thenReturn(generatePaymentKey());

        paymentService.reportFail(failReq);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // 2차 재시도 (confirm 호출 → 상태 DONE)
        TestTransaction.start(); // 새로운 트랜잭션 시작

        var retryReq = mock(ConfirmTossPayRequest.class);
        var newPaymentKey = generatePaymentKey();
        when(retryReq.getOrderId()).thenReturn(orderId);
        when(retryReq.getAmount()).thenReturn(amount);
        when(retryReq.getPaymentKey()).thenReturn(newPaymentKey);

        var resp = mock(ConfirmTossPayResponse.class);
        when(resp.getPaymentKey()).thenReturn(newPaymentKey);
        when(resp.getStatus()).thenReturn("DONE");
        when(resp.getMethod()).thenReturn("CARD");
        when(tossPaymentClient.confirmPayment(retryReq)).thenReturn(resp);

        // when
        paymentService.confirm(retryReq);

        // then
        Payment p = paymentRepository.findByTossOrderId(orderId).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Status.DONE);
        assertThat(p.getMethod()).isEqualTo(Method.CARD);
        assertThat(p.getTossPaymentKey()).isEqualTo(newPaymentKey);

        Wallet refreshed = walletRepository.findByUser(user).orElseThrow();
        assertThat(refreshed.getPostedBalance()).isEqualTo(amount);
    }


    /* 결제 실패 기록 메서드 */
    @Test
    void 결제_실패_로그가_정상적으로_저장된다() {
        // given
        var orderId = generateOrderId();
        long amount = 3_000L;

        // 1차 실패
        var failReq = mock(ConfirmTossPayRequest.class);
        when(failReq.getOrderId()).thenReturn(orderId);
        when(failReq.getAmount()).thenReturn(amount);
        when(failReq.getPaymentKey()).thenReturn(generatePaymentKey());

        // when
        paymentService.reportFail(failReq);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        TestTransaction.start();
        Pageable pageable = PageRequest.of(0, 10);
        Wallet refreshed = walletRepository.findByUser(user).orElseThrow();
        Page<WalletTransaction> failed = walletTransactionRepository
                .findByWalletAndTypeAndWalletTransactionStatus(
                        refreshed,
                        Type.CHARGE,
                        WalletTransactionStatus.FAILED,
                        pageable
                );
        assertThat(failed.hasContent()).isTrue();
    }

    @Test
    void 동일한_paymentKey에_대한_중복_실패_로그는_남지_않는다() {
        var orderId = generateOrderId();
        long amount = 3_000L;
        var paymentKey = generatePaymentKey();

        ConfirmTossPayRequest failReq = ConfirmTossPayRequest.builder()
                .orderId(orderId)
                .amount(amount)
                .paymentKey(paymentKey)
                .build();

        // 1) 첫 번째 실패 기록
        paymentService.reportFail(failReq);
        entityManager.clear();

        // Payment 기준으로 연결된 단일 트랜잭션이 존재하는지 확인
        Payment payment = paymentRepository.findByTossOrderId(orderId).orElseThrow();
        WalletTransaction firstTx = payment.getWalletTransaction();
        assertThat(firstTx).isNotNull();
        Long firstId = firstTx.getWalletTransactionId();

        // 2) 같은 키로 다시 실패 기록
        paymentService.reportFail(failReq);
        entityManager.clear();

        Payment paymentAfter = paymentRepository.findByTossOrderId(orderId).orElseThrow();
        WalletTransaction afterTx = paymentAfter.getWalletTransaction();

        assertThat(afterTx).isNotNull();
        assertThat(afterTx.getWalletTransactionId()).isEqualTo(firstId);
        assertThat(afterTx.getWalletTransactionStatus()).isEqualTo(WalletTransactionStatus.FAILED);
    }



}
