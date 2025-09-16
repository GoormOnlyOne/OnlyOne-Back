package com.example.onlyone.domain.feed.service;

import com.example.onlyone.OnlyoneApplication;
import com.example.onlyone.config.RedisTestConfig;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.payment.service.PaymentService;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;

import com.example.onlyone.global.stream.FeedLikeStreamConsumer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@SpringBootTest(classes = OnlyoneApplication.class)
@Import(RedisTestConfig.class)
class FeedServiceRedisLikeConcurrencyTest {

    // ---- Testcontainers: Redis 7 ----
    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        redisContainer.start();
        r.add("spring.data.redis.host", () -> redisContainer.getHost());
        r.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        // Lettuce commandTimeout은 BLOCK보다 길게 (유휴 타임아웃 예외 방지)
        r.add("spring.data.redis.timeout", () -> "10s");
    }

    // ---- Beans ----
    @Autowired private FeedService feedService;
    @Autowired private ClubRepository clubRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserClubRepository userClubRepository;
    @Autowired private InterestRepository interestRepository;
    @Autowired private FeedRepository feedRepository;
    @Autowired private FeedCommentRepository feedCommentRepository;
    @Autowired private FeedLikeRepository feedLikeRepository;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private FeedLikeStreamConsumer consumer;

    // 외부 의존은 Mock
    @MockitoBean
    private UserService userService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PaymentService paymentService;

    private TransactionTemplate txTemplate;

    private Pageable pageable;
    private Interest exerciseInterest;
    private Interest cultureInterest;
    private User testUser1, testUser2, testUser3;
    private Club exerciseClubInSeoul, cultureClubInSeoul, exerciseClubInBusan;

    @BeforeEach
    void setUp() throws InterruptedException {
        // 1) 스트림 보장 (더미 이벤트)
        try {
            stringRedisTemplate.opsForStream()
                    .add(FeedLikeStreamConsumer.STREAM, Map.of("init","1"));
        } catch (Exception ignore) {}

        // 2) 그룹 보장 (과거 레코드 스킵하려면 latest)
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(FeedLikeStreamConsumer.STREAM, ReadOffset.latest(), FeedLikeStreamConsumer.GROUP);
        } catch (Exception ignore) {}

        // 3) 컨슈머 실행 보장
        if (!consumer.isRunning()) consumer.start();

        // 4) 짧은 워밍업
        Thread.sleep(100);

        this.txTemplate = new TransactionTemplate(txm);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        this.pageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());

        // ----- 기본 데이터 -----
        Interest culture = Interest.builder().category(Category.CULTURE).build();
        Interest exercise = Interest.builder().category(Category.EXERCISE).build();
        Interest travel = Interest.builder().category(Category.TRAVEL).build();
        Interest music = Interest.builder().category(Category.MUSIC).build();
        Interest craft = Interest.builder().category(Category.CRAFT).build();
        Interest social = Interest.builder().category(Category.SOCIAL).build();
        Interest language = Interest.builder().category(Category.LANGUAGE).build();
        Interest finance = Interest.builder().category(Category.FINANCE).build();

        List<Interest> allInterests = interestRepository.saveAll(
                List.of(culture, exercise, travel, music, craft, social, language, finance));

        exerciseInterest = allInterests.stream().filter(i -> i.getCategory() == Category.EXERCISE).findFirst().orElseThrow();
        cultureInterest  = allInterests.stream().filter(i -> i.getCategory() == Category.CULTURE ).findFirst().orElseThrow();

        testUser1 = User.builder()
                .kakaoId(12345L).nickname("테스트유저1").status(Status.ACTIVE)
                .gender(Gender.MALE).birth(LocalDate.of(1990,1,1)).city("서울").district("강남구").build();

        testUser2 = User.builder()
                .kakaoId(12346L).nickname("테스트유저2").status(Status.ACTIVE)
                .gender(Gender.FEMALE).birth(LocalDate.of(1995,5,15)).city("서울").district("강남구").build();

        testUser3 = User.builder()
                .kakaoId(12347L).nickname("테스트유저3").status(Status.ACTIVE)
                .gender(Gender.MALE).birth(LocalDate.of(1985,12,20)).city("부산").district("해운대구").build();

        userRepository.saveAll(List.of(testUser1, testUser2, testUser3));

        exerciseClubInSeoul = Club.builder()
                .name("서울 축구 클럽").description("서울에서 함께 축구해요!")
                .userLimit(20).city("서울").district("강남구")
                .interest(exerciseInterest).clubImage("soccer.jpg").build();

        cultureClubInSeoul = Club.builder()
                .name("서울 독서 모임").description("책을 읽고 토론해요")
                .userLimit(15).city("서울").district("강남구")
                .interest(cultureInterest).clubImage("book.jpg").build();

        exerciseClubInBusan = Club.builder()
                .name("부산 테니스 클럽").description("부산에서 테니스 치실 분!")
                .userLimit(1).city("부산").district("해운대구")
                .interest(exerciseInterest).clubImage("tennis.jpg").build();

        clubRepository.saveAll(List.of(exerciseClubInSeoul, cultureClubInSeoul, exerciseClubInBusan));
        userClubRepository.saveAll(List.of(
                UserClub.builder().user(testUser1).club(exerciseClubInSeoul).clubRole(ClubRole.LEADER).build(),
                UserClub.builder().user(testUser2).club(exerciseClubInSeoul).clubRole(ClubRole.MEMBER).build(),
                UserClub.builder().user(testUser3).club(exerciseClubInBusan).clubRole(ClubRole.LEADER).build()
        ));
    }

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션과 분리된 별도 트랜잭션에서 하드 삭제
        var def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var status = txm.getTransaction(def);
        try {
            em.createNativeQuery("DELETE FROM feed_like").executeUpdate();
            em.createNativeQuery("DELETE FROM feed_comment").executeUpdate();
            em.createNativeQuery("DELETE FROM feed_image").executeUpdate();
            em.createNativeQuery("DELETE FROM feed").executeUpdate();
            em.createNativeQuery("DELETE FROM user_club").executeUpdate();
            em.createNativeQuery("DELETE FROM club").executeUpdate();
            em.createNativeQuery("DELETE FROM user").executeUpdate();
            em.createNativeQuery("DELETE FROM interest").executeUpdate();
            txm.commit(status);
        } catch (RuntimeException ex) {
            txm.rollback(status);
            throw ex;
        }
        // Redis 키도 정리(테스트 사이 간섭 방지)
        // 주의: 실서비스 키 접두와 동일해야 함
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void sanity() {
        assertThat(consumer.isRunning()).isTrue(); // false면 컨슈머가 안 돌아요
    }


    @DisplayName("N명의 서로 다른 유저가 동시에 좋아요 시도 → 각 사용자당 1개씩만 생성되고, DB like_count도 수렴한다")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_like_manyUsers_redisFirst() throws Exception {
        // ---- 준비: 별도 트랜잭션으로 클럽/유저/피드 커밋 ----
        record Prepared(Long clubId, Long feedId, List<User> users) {}
        Prepared prepared = txTemplate.execute(status -> {
            Interest ex = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());
            Club club = clubRepository.save(Club.builder()
                    .name("서울 축구 클럽 - 동시성")
                    .description("concurrency")
                    .userLimit(500)
                    .city("서울").district("강남구")
                    .interest(ex)
                    .clubImage("soccer.jpg")
                    .build());

            // 부하 크기 (CI/로컬 환경 고려)
            int N = 1000;
            List<User> users = new ArrayList<>(N);
            IntStream.range(0, N).forEach(i -> users.add(User.builder()
                    .kakaoId(30000L + i)
                    .nickname("U" + i)
                    .status(Status.ACTIVE)
                    .gender(i % 2 == 0 ? Gender.MALE : Gender.FEMALE)
                    .birth(LocalDate.of(1990, 1, 1))
                    .city("서울").district("강남구")
                    .build()));
            userRepository.saveAll(users);
            userClubRepository.saveAll(users.stream()
                    .map(u -> UserClub.builder().user(u).club(club).clubRole(ClubRole.MEMBER).build())
                    .toList());

            // 피드 1개 생성(작성자: 첫 번째 유저)
            when(userService.getCurrentUser()).thenReturn(users.get(0));
            feedService.createFeed(club.getClubId(),
                    com.example.onlyone.domain.feed.dto.request.FeedRequestDto.builder().feedUrls(List.of("x.jpg")).content("c").build());

            Long feedId = feedRepository.findAll().getLast().getFeedId();
            return new Prepared(club.getClubId(), feedId, users);
        });

        Long clubId = prepared.clubId();
        Long feedId = prepared.feedId();
        List<User> users = prepared.users();

        // ---- 동시 실행 ----
        ConcurrentLinkedQueue<User> queue = new ConcurrentLinkedQueue<>(users);
        reset(userService);
        when(userService.getCurrentUser()).thenAnswer(inv -> {
            User u = queue.poll();
            if (u == null) throw new IllegalStateException("getCurrentUser() called more than users.size()");
            return u;
        });

        int threads = users.size();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 32));

        List<Callable<Boolean>> tasks = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startGate.await();
                return feedService.toggleLike(clubId, feedId);
            });
        }

        startGate.countDown();
        List<Future<Boolean>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        // ---- 검증 A: Redis 멤버십 Set 크기 == 사용자 수 ----
        String likersKey = "feed:" + feedId + ":likers";
        Long scard = stringRedisTemplate.opsForSet().size(likersKey);
        assertThat(scard).as("Redis likers SCARD").isEqualTo((long) users.size());

//        // ---- 검증 B: DB like_count가 결국 N으로 수렴 (컨슈머 반영 대기) ----
        assertEventually(Duration.ofSeconds(20), Duration.ofMillis(50), () -> {
            Long lc = em.createQuery("select f.likeCount from Feed f where f.feedId = :id", Long.class)
                    .setParameter("id", feedId)
                    .getSingleResult();
            return lc != null && lc == users.size();
        });

        // (선택) 토글 결과 true/false의 개수 확인 (여기선 모두 'ON'이어야 하므로 true count == N)
        long trues = futures.stream().filter(f -> {
            try { return Boolean.TRUE.equals(f.get()); } catch (Exception e) { return false; }
        }).count();
        assertThat(trues).isEqualTo(users.size());
    }

    // 간단한 eventually 유틸 (Awaitility 미사용)
    private static void assertEventually(Duration timeout, Duration interval, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(interval.toMillis());
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}
