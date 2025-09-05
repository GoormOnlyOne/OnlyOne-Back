package com.example.onlyone.domain.feed.service;

import com.example.onlyone.OnlyoneApplication;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.entity.FeedLike;
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
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(classes = OnlyoneApplication.class)
@Transactional
class FeedServiceTest {
    @Autowired
    private TransactionTemplate txTemplate;
    @Autowired private FeedService feedService;

    @Autowired private ClubRepository clubRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserClubRepository userClubRepository;
    @Autowired private InterestRepository interestRepository;
    @Autowired
    private FeedRepository feedRepository;
    @Autowired
    private FeedCommentRepository feedCommentRepository;
    @Autowired
    private FeedLikeRepository feedLikeRepository;

    @MockitoBean private UserService userService;          // 외부 의존만 목킹
    @MockitoBean
    private NotificationService notificationService;
    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private EntityManager em; // 이미지 교체 검증 시 1차 캐시 초기화를 위해 사용

    private Interest exerciseInterest;
    private Interest cultureInterest;
    private User testUser1;
    private User testUser2;
    private User testUser3;
    private Club exerciseClubInSeoul;
    private Club cultureClubInSeoul;
    private Club exerciseClubInBusan;
    private Pageable pageable;

    private static final int pageNumber = 0;
    private static final int pageSize = 20;

    @BeforeEach
    void setUp() {
        this.pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());

        // 관심사 데이터 - 8개 카테고리 모두 생성
        Interest culture = Interest.builder().category(Category.CULTURE).build();
        Interest exercise = Interest.builder().category(Category.EXERCISE).build();
        Interest travel = Interest.builder().category(Category.TRAVEL).build();
        Interest music = Interest.builder().category(Category.MUSIC).build();
        Interest craft = Interest.builder().category(Category.CRAFT).build();
        Interest social = Interest.builder().category(Category.SOCIAL).build();
        Interest language = Interest.builder().category(Category.LANGUAGE).build();
        Interest finance = Interest.builder().category(Category.FINANCE).build();

        List<Interest> allInterests = interestRepository.saveAll(List.of(
                culture, exercise, travel, music, craft, social, language, finance));

        exerciseInterest = allInterests.stream()
                .filter(i -> i.getCategory() == Category.EXERCISE)
                .findFirst().orElseThrow();

        cultureInterest = allInterests.stream()
                .filter(i -> i.getCategory() == Category.CULTURE)
                .findFirst().orElseThrow();

        // 사용자 데이터
        testUser1 = User.builder()
                .kakaoId(12345L)
                .nickname("테스트유저1")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1990, 1, 1))
                .city("서울")
                .district("강남구")
                .build();

        testUser2 = User.builder()
                .kakaoId(12346L)
                .nickname("테스트유저2")
                .status(Status.ACTIVE)
                .gender(Gender.FEMALE)
                .birth(LocalDate.of(1995, 5, 15))
                .city("서울")
                .district("강남구")
                .build();

        testUser3 = User.builder()
                .kakaoId(12347L)
                .nickname("테스트유저3")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1985, 12, 20))
                .city("부산")
                .district("해운대구")
                .build();

        userRepository.saveAll(List.of(testUser1, testUser2, testUser3));

        // 클럽 데이터
        exerciseClubInSeoul = Club.builder()
                .name("서울 축구 클럽")
                .description("서울에서 함께 축구해요!")
                .userLimit(20)
                .city("서울")
                .district("강남구")
                .interest(exerciseInterest)
                .clubImage("soccer.jpg")
                .build();

        cultureClubInSeoul = Club.builder()
                .name("서울 독서 모임")
                .description("책을 읽고 토론해요")
                .userLimit(15)
                .city("서울")
                .district("강남구")
                .interest(cultureInterest)
                .clubImage("book.jpg")
                .build();

        exerciseClubInBusan = Club.builder()
                .name("부산 테니스 클럽")
                .description("부산에서 테니스 치실 분!")
                .userLimit(1)
                .city("부산")
                .district("해운대구")
                .interest(exerciseInterest)
                .clubImage("tennis.jpg")
                .build();

        clubRepository.saveAll(List.of(exerciseClubInSeoul, cultureClubInSeoul, exerciseClubInBusan));

        // UserClub 관계 설정
        UserClub userClub1 = UserClub.builder()
                .user(testUser1)
                .club(exerciseClubInSeoul)
                .clubRole(ClubRole.LEADER)
                .build();

        UserClub userClub2 = UserClub.builder()
                .user(testUser2)
                .club(exerciseClubInSeoul)
                .clubRole(ClubRole.MEMBER)
                .build();

        UserClub userClub3 = UserClub.builder()
                .user(testUser3)
                .club(exerciseClubInBusan)
                .clubRole(ClubRole.LEADER)
                .build();

        userClubRepository.saveAll(List.of(userClub1, userClub2, userClub3));
    }

    @AfterEach
    void afterEachCleanup() {
        // 테스트 트랜잭션과 분리된 새로운 트랜잭션에서 DB를 정리한다
        txTemplate.execute(status -> {
            em.createNativeQuery("DELETE FROM feed_like").executeUpdate();
            em.createNativeQuery("DELETE FROM feed_comment").executeUpdate();
            em.createNativeQuery("DELETE FROM feed_image").executeUpdate();

            // 부모 (소프트삭제 조건 무시, 전부 하드 삭제)
            em.createNativeQuery("DELETE FROM feed").executeUpdate();

            em.createNativeQuery("DELETE FROM user_club").executeUpdate();
            em.createNativeQuery("DELETE FROM club").executeUpdate();
            em.createNativeQuery("DELETE FROM user").executeUpdate();
            em.createNativeQuery("DELETE FROM interest").executeUpdate();
            return null;
        });
    }

    @DisplayName("모임에 가입한 사용자만 피드를 생성할 수 있다.")
    @Test
    void createFeed_success_whenMember() {
        //given
        when(userService.getCurrentUser()).thenReturn(testUser2);
        FeedRequestDto dto = FeedRequestDto.builder()
                .feedUrls(List.of("img1.jpg", "img2.jpg"))
                .content("운동 인증!")
                .build();

        long before = feedRepository.count();

        // when
        feedService.createFeed(exerciseClubInSeoul.getClubId(), dto);

        // then
        assertThat(feedRepository.count()).isEqualTo(before + 1);
    }


    @DisplayName("모임에 가입하지 않은 자는 피드를 생성 할 수 없다.")
    @Test
    void createFeed_nonMember_throws() {
        //given
        when(userService.getCurrentUser()).thenReturn(testUser3);
        FeedRequestDto dto = FeedRequestDto.builder()
                .feedUrls(List.of("a.jpg"))  // 최소 1개
                .content("hello")
                .build();

        // when & then
        assertThatThrownBy(() -> feedService.createFeed(exerciseClubInSeoul.getClubId(), dto))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_NOT_JOIN);
    }

    @DisplayName("피드 작성자만 피드를 정상적으로 수정할 수 있다.")
    @Test
    void updateFeed_success_whenAuthor() {
        // given: testUser2(멤버)가 피드를 생성
        when(userService.getCurrentUser()).thenReturn(testUser2);
        FeedRequestDto createReq = FeedRequestDto.builder()
                .feedUrls(List.of("a.jpg"))
                .content("원본 내용")
                .build();
        feedService.createFeed(exerciseClubInSeoul.getClubId(), createReq);


        // 방금 생성된 피드 조회 (트랜잭션 롤백 환경이라 단 하나일 것)
        Feed created = feedRepository.findAll().getFirst();
        Long feedId = created.getFeedId();

        // when: 같은 작성자(testUser2)가 수정 시도
        when(userService.getCurrentUser()).thenReturn(testUser2);
        FeedRequestDto updateReq = FeedRequestDto.builder()
                .feedUrls(List.of("b.jpg", "c.jpg"))
                .content("수정된 내용")
                .build();

        feedService.updateFeed(exerciseClubInSeoul.getClubId(), feedId, updateReq);

        // then
        Feed updated = feedRepository.findById(feedId).orElseThrow();
        assertThat(updated.getContent()).isEqualTo("수정된 내용");
        assertThat(updated.getUser().getUserId()).isEqualTo(testUser2.getUserId());
        assertThat(updated.getClub().getClubId()).isEqualTo(exerciseClubInSeoul.getClubId());
    }

    @DisplayName("피드 작성자 외 사용자가 피드를 수정하려 하면 예외가 발생한다.")
    @Test
    void updateFeed_throws_whenNotAuthor() {
        // given: testUser1(리더)가 피드를 생성
        when(userService.getCurrentUser()).thenReturn(testUser1);
        FeedRequestDto createReq = FeedRequestDto.builder()
                .feedUrls(List.of("a.jpg"))
                .content("원본 내용")
                .build();
        feedService.createFeed(exerciseClubInSeoul.getClubId(), createReq);

        Feed created = feedRepository.findAll().getFirst();
        Long feedId = created.getFeedId();

        // when: 다른 사용자(testUser2)가 수정 시도
        when(userService.getCurrentUser()).thenReturn(testUser2);
        FeedRequestDto updateReq = FeedRequestDto.builder()
                .feedUrls(List.of("b.jpg"))
                .content("남이 수정하려는 내용")
                .build();

        // then
        assertThatThrownBy(() ->
                feedService.updateFeed(exerciseClubInSeoul.getClubId(), feedId, updateReq)
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED_FEED_ACCESS);
    }

    @DisplayName("본문(content)이 정상 변경된다.")
    @Test
    void updateFeed_updatesContent() {
        when(userService.getCurrentUser()).thenReturn(testUser1);
        feedService.createFeed(exerciseClubInSeoul.getClubId(),
                FeedRequestDto.builder()
                        .feedUrls(List.of("old1.jpg"))
                        .content("원본 내용")
                        .build());

        Long feedId = feedRepository.findAll().get(0).getFeedId();

        when(userService.getCurrentUser()).thenReturn(testUser1);
        feedService.updateFeed(exerciseClubInSeoul.getClubId(), feedId,
                FeedRequestDto.builder()
                        .feedUrls(List.of("old1.jpg"))
                        .content("수정된 내용")
                        .build());

        Feed updated = feedRepository.findById(feedId).orElseThrow();
        assertThat(updated.getContent()).isEqualTo("수정된 내용");
    }

    @DisplayName("기존 이미지가 전부 제거되고 요청 이미지로 '순서대로' 완전히 대체된다.")
    @Test
    void updateFeed_replacesAllImages_inOrder() {
        when(userService.getCurrentUser()).thenReturn(testUser1);
        feedService.createFeed(exerciseClubInSeoul.getClubId(),
                FeedRequestDto.builder()
                        .feedUrls(List.of("old1.jpg", "old2.jpg"))
                        .content("이미지 교체 테스트")
                        .build());

        Feed created = feedRepository.findAll().get(0);
        Long feedId = created.getFeedId();

        // 초기 순서 검증
        assertThat(created.getFeedImages())
                .extracting(FeedImage::getFeedImage)
                .containsExactly("old1.jpg", "old2.jpg");

        when(userService.getCurrentUser()).thenReturn(testUser1);
        feedService.updateFeed(exerciseClubInSeoul.getClubId(), feedId,
                FeedRequestDto.builder()
                        .feedUrls(List.of("new1.jpg", "new2.jpg", "new3.jpg"))
                        .content("본문 변경")
                        .build());

        // 1차 캐시 비우고 다시 조회해서 순서/치환 확정 검증
        em.flush();
        em.clear();

        Feed updated = feedRepository.findById(feedId).orElseThrow();
        assertThat(updated.getFeedImages())
                .extracting(FeedImage::getFeedImage)
                .containsExactly("new1.jpg", "new2.jpg", "new3.jpg");
    }

    @DisplayName("모임에서 피드 목록들이 최신 순서로 정상적으로 조회되며 각 피드의 썸네일은 첫 번재 이미지로 보여지는가?")
    @Test
    void getFeedList_returnsFeedsOfClub_withThumbnailAndCounts() {
        // given: 같은 클럽에 원글 2개, 다른 클럽에 원글 1개
        Feed feedA = Feed.builder()
                .content("A")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feedA.getFeedImages().add(FeedImage.builder().feedImage("a1.jpg").feed(feedA).build());
        feedA.getFeedImages().add(FeedImage.builder().feedImage("a2.jpg").feed(feedA).build());
        feedRepository.save(feedA);

        Feed feedB = Feed.builder()
                .content("B")
                .club(exerciseClubInSeoul)
                .user(testUser2)
                .build();
        feedB.getFeedImages().add(FeedImage.builder().feedImage("b1.jpg").feed(feedB).build());
        feedRepository.save(feedB);

        // 다른 클럽의 피드(결과에 포함되면 안 됨)
        Feed otherClubFeed = Feed.builder()
                .content("X")
                .club(cultureClubInSeoul)
                .user(testUser1)
                .build();
        feedRepository.save(otherClubFeed);

        // when
        Page<FeedSummaryResponseDto> page =
                feedService.getFeedList(exerciseClubInSeoul.getClubId(), pageable);

        // then: 같은 클럽의 원글 2개만
        assertThat(page.getTotalElements()).isEqualTo(2);

        assertThat(page.getContent())
                .extracting(FeedSummaryResponseDto::getFeedId)
                .containsExactlyInAnyOrder(feedA.getFeedId(), feedB.getFeedId());

        // 썸네일: 첫 번째 이미지가 선택되고, 이미지 없는 피드는 null
        FeedSummaryResponseDto dtoA = page.getContent().stream()
                .filter(d -> d.getFeedId().equals(feedA.getFeedId()))
                .findFirst().orElseThrow();
        assertThat(dtoA.getThumbnailUrl()).isEqualTo("a1.jpg");

        FeedSummaryResponseDto dtoB = page.getContent().stream()
                .filter(d -> d.getFeedId().equals(feedB.getFeedId()))
                .findFirst().orElseThrow();
        assertThat(dtoB.getThumbnailUrl()).isEqualTo("b1.jpg");
    }

    @DisplayName("리피드가 아닌 원글만 조회된다( parentFeedId IS NULL 만 )")
    @Test
    void getFeedList_excludesRefeed() {
        // given
        Feed original = Feed.builder()
                .content("orig")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feedRepository.save(original);

        // 리피드
        Feed refeed = Feed.builder()
                .content("refeed")
                .club(exerciseClubInSeoul)
                .user(testUser2)
                .parentFeedId(original.getFeedId())
                .rootFeedId(original.getFeedId())
                .build();
        feedRepository.save(refeed);

        // when
        Page<FeedSummaryResponseDto> page =
                feedService.getFeedList(exerciseClubInSeoul.getClubId(),pageable);

        // then: 원글만 1개
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent())
                .extracting(FeedSummaryResponseDto::getFeedId)
                .containsExactly(original.getFeedId()); // 리피드 ID는 없어야 함
    }

    @DisplayName("피드 목록에서 각 피드의 좋아요/댓글 수가 정확히 반영된다")
    @Test
    void getFeedList_likeAndCommentCounts_areAccurate() {
        // given: 같은 클럽에 원글 2개(이미지 필수)
        Feed feed1 = saveFeedWithImage(exerciseClubInSeoul,testUser1,"F1","f1.jpg");
        Feed feed2 = saveFeedWithImage(exerciseClubInSeoul,testUser1,"F2","f2.jpg");

        // likes: feed1 -> 3, feed2 -> 1
        feedLikeRepository.save(FeedLike.builder().feed(feed1).user(testUser1).build());
        feedLikeRepository.save(FeedLike.builder().feed(feed1).user(testUser2).build());
        feedLikeRepository.save(FeedLike.builder().feed(feed1).user(testUser3).build());
        feedLikeRepository.save(FeedLike.builder().feed(feed2).user(testUser1).build());

        // comments: feed1 -> 2, feed2 -> 0
        feedCommentRepository.save(FeedComment.builder().feed(feed1).user(testUser2).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(feed1).user(testUser3).content("c2").build());

        em.flush();
        em.clear();

        // when
        Page<FeedSummaryResponseDto> page =
                feedService.getFeedList(exerciseClubInSeoul.getClubId(), pageable);

        // then: 각 피드별 집계값 검증
        Map<Long, FeedSummaryResponseDto> byId = page.getContent().stream()
                .collect(Collectors.toMap(FeedSummaryResponseDto::getFeedId, Function.identity()));

        FeedSummaryResponseDto s1 = byId.get(feed1.getFeedId());
        FeedSummaryResponseDto s2 = byId.get(feed2.getFeedId());

        assertThat(s1).isNotNull();
        assertThat(s2).isNotNull();

        assertThat(s1.getLikeCount()).isEqualTo(3);
        assertThat(s1.getCommentCount()).isEqualTo(2);

        assertThat(s2.getLikeCount()).isEqualTo(1);
        assertThat(s2.getCommentCount()).isEqualTo(0);
    }

    @DisplayName("피드 상세 조회가 정상적으로 조회된다")
    @Test
    void getFeedDetail_returnsDetailSuccessfully() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        Feed feed = saveFeedWithImage(exerciseClubInSeoul,testUser1,"hello detail","d1.jpg");

        // when
        FeedDetailResponseDto dto = feedService.getFeedDetail(exerciseClubInSeoul.getClubId(), feed.getFeedId());

        // then
        assertThat(dto).isNotNull();
        assertThat(dto.getFeedId()).isEqualTo(feed.getFeedId());
        assertThat(dto.getContent()).isEqualTo("hello detail");
        assertThat(dto.getImageUrls()).containsExactly("d1.jpg");
    }

    @DisplayName("이미지 URL 리스트가 저장 순서대로 반환된다")
    @Test
    void getFeedDetail_imageUrls_inInsertionOrder() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        Feed feed = Feed.builder()
                .content("with images")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("img-1.jpg").feed(feed).build());
        feed.getFeedImages().add(FeedImage.builder().feedImage("img-2.jpg").feed(feed).build());
        feed.getFeedImages().add(FeedImage.builder().feedImage("img-3.jpg").feed(feed).build());
        feedRepository.save(feed);

        // when
        FeedDetailResponseDto dto = feedService.getFeedDetail(exerciseClubInSeoul.getClubId(), feed.getFeedId());

        // then
        assertThat(dto.getImageUrls()).containsExactly("img-1.jpg", "img-2.jpg", "img-3.jpg");
    }

    @DisplayName("현재 사용자의 좋아요 여부(isLiked), 소유 여부(isMine), 리피드 개수가 정확하다")
    @Test
    void getFeedDetail_flagsAndRepostCount_areAccurate() {
        // given: 작성자는 testUser1, 현재 사용자도 testUser1
        when(userService.getCurrentUser()).thenReturn(testUser1);

        Feed feed = Feed.builder()
                .content("flags")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("f.jpg").feed(feed).build());
        feedRepository.save(feed);

        // 좋아요: 현재 사용자(testUser1)와 다른 사용자도 누름
        feedLikeRepository.save(FeedLike.builder().feed(feed).user(testUser1).build());
        feedLikeRepository.save(FeedLike.builder().feed(feed).user(testUser2).build());

        // 리피드 2개
        Feed re1 = Feed.builder()
                .content("re1")
                .club(exerciseClubInSeoul)
                .user(testUser2)
                .parentFeedId(feed.getFeedId())
                .rootFeedId(feed.getFeedId())
                .build();
        re1.getFeedImages().add(FeedImage.builder().feedImage("r1.jpg").feed(re1).build());
        feedRepository.save(re1);

        Feed re2 = Feed.builder()
                .content("re2")
                .club(exerciseClubInSeoul)
                .user(testUser3)
                .parentFeedId(feed.getFeedId())
                .rootFeedId(feed.getFeedId())
                .build();
        re2.getFeedImages().add(FeedImage.builder().feedImage("r2.jpg").feed(re2).build());
        feedRepository.save(re2);

        em.flush();
        em.clear();

        // when
        FeedDetailResponseDto dto = feedService.getFeedDetail(exerciseClubInSeoul.getClubId(), feed.getFeedId());

        // then
        assertThat(dto.isLiked()).isTrue();          // 현재 사용자(testUser1)가 좋아요 눌렀음
        assertThat(dto.isFeedMine()).isTrue();           // 작성자 = 현재 사용자
        assertThat(dto.getRepostCount()).isEqualTo(2L);
    }

    @DisplayName("댓글이 오래된 순으로 반환된다")
    @Test
    void getFeedDetail_comments_sortedOldestFirst() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        Feed feed = Feed.builder()
                .content("with comments")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("c.jpg").feed(feed).build());
        feedRepository.save(feed);

        // 댓글 2개: 먼저 저장된 것이 먼저여야 한다(오래된 순)
        FeedComment c1 = FeedComment.builder()
                .feed(feed)
                .user(testUser2)
                .content("first")
                .build();
        FeedComment c2 = FeedComment.builder()
                .feed(feed)
                .user(testUser3)
                .content("second")
                .build();
        feedCommentRepository.save(c1);
        feedCommentRepository.save(c2);

        em.flush();
        em.clear();

        // when
        FeedDetailResponseDto dto = feedService.getFeedDetail(exerciseClubInSeoul.getClubId(), feed.getFeedId());

        // then
        List<String> contents = dto.getComments().stream()
                .map(FeedCommentResponseDto::getContent)
                .toList();

        assertThat(contents).containsExactly("first", "second");
    }

    @DisplayName("좋아요가 정상적으로 생성되는가?")
    @Test
    void toggleLike_creates_whenAbsent() {
        // given: 글 작성자는 testUser1, 현재 유저는 testUser2
        when(userService.getCurrentUser()).thenReturn(testUser2);

        Feed feed = Feed.builder()
                .content("hello")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        // 이미지 필수
        feed.getFeedImages().add(FeedImage.builder().feedImage("x.jpg").feed(feed).build());
        feedRepository.save(feed);

        assertThat(feedLikeRepository.countByFeed(feed)).isZero();

        // when
        boolean liked = feedService.toggleLike(exerciseClubInSeoul.getClubId(), feed.getFeedId());

        // then
        assertThat(liked).isTrue();
        assertThat(feedLikeRepository.countByFeed(feed)).isEqualTo(1);
    }

    @DisplayName("좋아요가 정상적으로 취소되는가?")
    @Test
    void toggleLike_cancels_whenPresent() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser2);

        Feed feed = Feed.builder()
                .content("hello")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("x.jpg").feed(feed).build());
        feedRepository.save(feed);

        // 먼저 생성 상태로 만들어 둠
        boolean firstToggle = feedService.toggleLike(exerciseClubInSeoul.getClubId(), feed.getFeedId());
        assertThat(firstToggle).isTrue();
        assertThat(feedLikeRepository.countByFeed(feed)).isEqualTo(1);

        // when: 다시 토글 → 취소
        boolean secondToggle = feedService.toggleLike(exerciseClubInSeoul.getClubId(), feed.getFeedId());

        // then
        assertThat(secondToggle).isFalse();
        assertThat(feedLikeRepository.countByFeed(feed)).isZero();
    }

    @DisplayName("댓글이 정상적으로 작성되는가?")
    @Test
    void createComment_success_member_canCreate() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser2);

        Feed feed = Feed.builder()
                .content("원글")
                .club(exerciseClubInSeoul)
                .user(testUser1) // 작성자
                .build();
        // 이미지 필수
        feed.getFeedImages().add(FeedImage.builder().feedImage("f.jpg").feed(feed).build());
        feedRepository.save(feed);

        FeedCommentRequestDto dto = FeedCommentRequestDto.builder()
                .content("첫 댓글!")
                .build();

        // when
        feedService.createComment(exerciseClubInSeoul.getClubId(), feed.getFeedId(), dto);

        // then:
        List<FeedComment> all = feedCommentRepository.findAll();
        assertThat(all).hasSize(1);
        FeedComment saved = all.get(0);

        assertThat(saved.getFeed().getFeedId()).isEqualTo(feed.getFeedId());
        assertThat(saved.getUser().getUserId()).isEqualTo(testUser2.getUserId());
        assertThat(saved.getContent()).isEqualTo("첫 댓글!");
    }

    @DisplayName("모임 미가입자는 댓글을 작성할 수 없다.")
    @Test
    void createComment_nonMember_throws() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser3);

        Feed feed = Feed.builder()
                .content("원글")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("f.jpg").feed(feed).build());
        feedRepository.save(feed);

        FeedCommentRequestDto dto = FeedCommentRequestDto.builder()
                .content("댓글 시도")
                .build();

        // when & then: CLUB_NOT_JOIN 예외
        assertThatThrownBy(() ->
                feedService.createComment(exerciseClubInSeoul.getClubId(), feed.getFeedId(), dto)
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_NOT_JOIN);
    }

    @DisplayName("댓글이 정상적으로 삭제된다 (댓글 작성자 본인이 삭제)")
    @Test
    void deleteComment_success_byCommentAuthor() {
        // given: feed 작성자 = testUser1, 댓글 작성자 = testUser2(현재 유저)
        when(userService.getCurrentUser()).thenReturn(testUser2);

        Feed feed = Feed.builder()
                .content("원글")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        // 이미지 필수
        feed.getFeedImages().add(FeedImage.builder().feedImage("f.jpg").feed(feed).build());
        feedRepository.save(feed);

        FeedComment comment = FeedComment.builder()
                .feed(feed)
                .user(testUser2)
                .content("삭제될 댓글")
                .build();
        feedCommentRepository.saveAndFlush(comment);

        long before = feedCommentRepository.count();

        // when
        feedService.deleteComment(exerciseClubInSeoul.getClubId(), feed.getFeedId(), comment.getFeedCommentId());
        feedCommentRepository.flush();

        // then
        assertThat(feedCommentRepository.existsById(comment.getFeedCommentId())).isFalse();
        assertThat(feedCommentRepository.count()).isEqualTo(before - 1);
    }

    @DisplayName("댓글이 정상적으로 삭제된다 (피드 작성자가 삭제)")
    @Test
    void deleteComment_success_byFeedAuthor() {
        // given: feed 작성자 = testUser1(현재 유저), 댓글 작성자 = testUser2
        when(userService.getCurrentUser()).thenReturn(testUser1);

        Feed feed = Feed.builder()
                .content("원글")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("f.jpg").feed(feed).build());
        feedRepository.save(feed);

        FeedComment comment = FeedComment.builder()
                .feed(feed)
                .user(testUser2)
                .content("삭제될 댓글")
                .build();
        feedCommentRepository.saveAndFlush(comment);

        long before = feedCommentRepository.count();

        // when
        feedService.deleteComment(exerciseClubInSeoul.getClubId(), feed.getFeedId(), comment.getFeedCommentId());
        feedCommentRepository.flush();

        // then
        assertThat(feedCommentRepository.existsById(comment.getFeedCommentId())).isFalse();
        assertThat(feedCommentRepository.count()).isEqualTo(before - 1);
    }

    @DisplayName("댓글 작성자나 피드 작성자가 아니면 삭제할 수 없다")
    @Test
    void deleteComment_unauthorized_throws() {
        // given: feed 작성자 = testUser1, 댓글 작성자 = testUser2, 현재 유저 = testUser3(권한 없음)
        when(userService.getCurrentUser()).thenReturn(testUser3);

        Feed feed = Feed.builder()
                .content("원글")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feed.getFeedImages().add(FeedImage.builder().feedImage("f.jpg").feed(feed).build());
        feedRepository.save(feed);

        FeedComment comment = FeedComment.builder()
                .feed(feed)
                .user(testUser2)
                .content("권한 없이 삭제 시도")
                .build();
        feedCommentRepository.saveAndFlush(comment);

        long before = feedCommentRepository.count();

        // when & then
        assertThatThrownBy(() ->
                feedService.deleteComment(exerciseClubInSeoul.getClubId(), feed.getFeedId(), comment.getFeedCommentId())
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED_COMMENT_ACCESS);

        // 삭제 안 됨
        assertThat(feedCommentRepository.existsById(comment.getFeedCommentId())).isTrue();
        assertThat(feedCommentRepository.count()).isEqualTo(before);
    }

    @DisplayName("루트 피드를 작성자가 삭제하면: 자식 parent/root 해제 + 후손 root 해제")
    @Test
    void softDeleteFeed_root_success() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        // 트리 구성: R(testUser1) -> C1(testUser2), C2(testUser3); C1 -> G1(testUser3)
        Feed root = saveFeedWithImage(exerciseClubInSeoul, testUser1, "R", "r.jpg");
        Feed c1 = saveFeedWithImage(exerciseClubInSeoul, testUser2, "C1", "c1.jpg");

        c1 = feedRepository.save(
                Feed.builder()
                        .feedId(c1.getFeedId()) // 이미 저장된 것을 다시 저장하려면 보통 필요 X, 여기선 set만
                        .content(c1.getContent())
                        .club(c1.getClub())
                        .user(c1.getUser())
                        .parentFeedId(root.getFeedId())
                        .rootFeedId(root.getFeedId())
                        .build()
        );

        Feed c2 = saveFeedWithImage(exerciseClubInSeoul, testUser3, "C2", "c2.jpg");
        c2 = feedRepository.save(
                Feed.builder()
                        .feedId(c2.getFeedId())
                        .content(c2.getContent())
                        .club(c2.getClub())
                        .user(c2.getUser())
                        .parentFeedId(root.getFeedId())
                        .rootFeedId(root.getFeedId())
                        .build()
        );

        Feed g1 = saveFeedWithImage(exerciseClubInSeoul, testUser3, "G1", "g1.jpg");
        g1 = feedRepository.save(
                Feed.builder()
                        .feedId(g1.getFeedId())
                        .content(g1.getContent())
                        .club(g1.getClub())
                        .user(g1.getUser())
                        .parentFeedId(c1.getFeedId())
                        .rootFeedId(root.getFeedId())
                        .build()
        );

        Long toDeleteId = root.getFeedId();
        Long c1Id = c1.getFeedId();
        Long c2Id = c2.getFeedId();
        Long g1Id = g1.getFeedId();

        // when
        feedService.softDeleteFeed(exerciseClubInSeoul.getClubId(), toDeleteId);

        // bulk update 후 1차 캐시 비우기
        em.flush();
        em.clear();

        // then
        assertThat(feedRepository.findByFeedIdAndClub(toDeleteId, exerciseClubInSeoul)).isEmpty(); // 소프트 삭제되어 안 보여야 함

        Feed c1Reload = feedRepository.findById(c1Id).orElseThrow();
        Feed c2Reload = feedRepository.findById(c2Id).orElseThrow();
        Feed g1Reload = feedRepository.findById(g1Id).orElseThrow();

        // 직계 자식은 parent/root 모두 null
        assertThat(c1Reload.getParentFeedId()).isNull();
        assertThat(c1Reload.getRootFeedId()).isNull();
        assertThat(c2Reload.getParentFeedId()).isNull();
        assertThat(c2Reload.getRootFeedId()).isNull();

        // 후손(G1)은 root==toDeletedId 였으므로 clearRootForDescendants 대상 → root null
        assertThat(g1Reload.getRootFeedId()).isNull();
        // parent는 C1(존재) 그대로 유지 (명세 상 직접 자식만 parent를 지움)
        assertThat(g1Reload.getParentFeedId()).isEqualTo(c1Id);
    }

    @DisplayName("중간 노드를 작성자가 삭제하면: 직계 자식 parent/root 해제, 다른 가지는 영향 없음")
    @Test
    void softDeleteFeed_middleNode_success() {
        // given
        // 현재 유저 = C1 작성자
        when(userService.getCurrentUser()).thenReturn(testUser2);

        Feed root = saveFeedWithImage(exerciseClubInSeoul, testUser1, "R", "r.jpg");
        Feed c1 = saveFeedWithImage(exerciseClubInSeoul, testUser2, "C1", "c1.jpg");
        c1 = feedRepository.save(
                Feed.builder()
                        .feedId(c1.getFeedId())
                        .content(c1.getContent())
                        .club(c1.getClub())
                        .user(c1.getUser())
                        .parentFeedId(root.getFeedId())
                        .rootFeedId(root.getFeedId())
                        .build()
        );

        Feed c2 = saveFeedWithImage(exerciseClubInSeoul, testUser3, "C2", "c2.jpg");
        c2 = feedRepository.save(
                Feed.builder()
                        .feedId(c2.getFeedId())
                        .content(c2.getContent())
                        .club(c2.getClub())
                        .user(c2.getUser())
                        .parentFeedId(root.getFeedId())
                        .rootFeedId(root.getFeedId())
                        .build()
        );

        Feed g1 = saveFeedWithImage(exerciseClubInSeoul, testUser3, "G1", "g1.jpg");
        g1 = feedRepository.save(
                Feed.builder()
                        .feedId(g1.getFeedId())
                        .content(g1.getContent())
                        .club(g1.getClub())
                        .user(g1.getUser())
                        .parentFeedId(c1.getFeedId())
                        .rootFeedId(root.getFeedId()) // 보통 최상위 루트를 가리킴
                        .build()
        );

        Long c1Id = c1.getFeedId();
        Long c2Id = c2.getFeedId();
        Long g1Id = g1.getFeedId();
        Long rootId = root.getFeedId();

        // when
        feedService.softDeleteFeed(exerciseClubInSeoul.getClubId(), c1Id);
        em.flush();
        em.clear();

        // then
        assertThat(feedRepository.findByFeedIdAndClub(c1Id, exerciseClubInSeoul)).isEmpty();

        Feed g1Reload = feedRepository.findById(g1Id).orElseThrow();
        Feed c2Reload = feedRepository.findById(c2Id).orElseThrow();

        // C1의 직계 자식(G1)은 parent/root 모두 null
        assertThat(g1Reload.getParentFeedId()).isNull();
        assertThat(g1Reload.getRootFeedId()).isNull();

        // 다른 가지(C2)는 영향 없음
        assertThat(c2Reload.getParentFeedId()).isEqualTo(rootId);
        assertThat(c2Reload.getRootFeedId()).isEqualTo(rootId);
    }

    @DisplayName("다른 사람이 작성한 피드는 삭제할 수 없다")
    @Test
    void softDeleteFeed_unauthorized_throws() {
        // given: feed 작성자 = testUser1, 현재 유저 = testUser2
        when(userService.getCurrentUser()).thenReturn(testUser2);

        Feed feed = saveFeedWithImage(exerciseClubInSeoul, testUser1, "R", "r.jpg");
        Long feedId = feed.getFeedId();

        // when & then
        assertThatThrownBy(() ->
                feedService.softDeleteFeed(exerciseClubInSeoul.getClubId(), feedId)
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED_FEED_ACCESS);

        // 여전히 존재 (소프트 삭제 안 됨)
        em.clear();
        assertThat(feedRepository.findByFeedIdAndClub(feedId, exerciseClubInSeoul)).isPresent();
    }

    @DisplayName("존재하지 않는 클럽의 피드 삭제 시 예외")
    @Test
    void softDeleteFeed_notFound_throws() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        Feed feed = saveFeedWithImage(exerciseClubInSeoul, testUser1, "R", "r.jpg");

        // 잘못된 clubId
        assertThatThrownBy(() ->
                feedService.softDeleteFeed(Long.MAX_VALUE, feed.getFeedId())
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_NOT_FOUND);

        // 잘못된 feedId
        assertThatThrownBy(() ->
                feedService.softDeleteFeed(exerciseClubInSeoul.getClubId(), Long.MAX_VALUE)
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FEED_NOT_FOUND);
    }

    @DisplayName("리피드 삭제 후 동일 user/club/parent로 리피드 재생성이 가능하다")
    @Test
    void recreateRefeed_afterSoftDelete_allowedForSameUserClubParent() {
        // given: 루트(feedRoot)와 그 리피드(oldRefeed) 생성
        Feed feedRoot = Feed.builder()
                .content("root")
                .club(exerciseClubInSeoul)
                .user(testUser1)
                .build();
        feedRoot.getFeedImages().add(FeedImage.builder().feedImage("root.jpg").feed(feedRoot).build());
        feedRepository.saveAndFlush(feedRoot);

        Feed oldRefeed = Feed.builder()
                .content("first refeed")
                .club(exerciseClubInSeoul)
                .user(testUser2)                              // 동일 user로 재생성 테스트
                .parentFeedId(feedRoot.getFeedId())
                .rootFeedId(feedRoot.getFeedId())
                .build();
        oldRefeed.getFeedImages().add(FeedImage.builder().feedImage("r1.jpg").feed(oldRefeed).build());
        feedRepository.saveAndFlush(oldRefeed);

        // 삭제 권한: 리피드 작성자
        when(userService.getCurrentUser()).thenReturn(testUser2);

        // when: 먼저 기존 리피드를 soft delete
        feedService.softDeleteFeed(exerciseClubInSeoul.getClubId(), oldRefeed.getFeedId());

        // then: 같은 user/club/parent 로 새 리피드 생성 시 unique 제약(활성 리피드 1개) 위반 없이 성공해야 함
        Feed newRefeed = Feed.builder()
                .content("second refeed after delete")
                .club(exerciseClubInSeoul)
                .user(testUser2)
                .parentFeedId(feedRoot.getFeedId())
                .rootFeedId(feedRoot.getFeedId())
                .build();
        newRefeed.getFeedImages().add(FeedImage.builder().feedImage("r2.jpg").feed(newRefeed).build());

        // 저장이 예외 없이 완료되어야 함 (활성 중복 리피드 unique 제약은 삭제로 해제됨)
        feedRepository.saveAndFlush(newRefeed);

        // 활성 리피드 집계: parent 기준 1개(방금 만든 것)
        long aliveChildren = feedRepository.countByParentFeedId(feedRoot.getFeedId());
        assertThat(aliveChildren).isEqualTo(1L);

        // 새 리피드가 정상 조회된다
        Club club = clubRepository.findById(exerciseClubInSeoul.getClubId()).orElseThrow();
        assertThat(feedRepository.findByFeedIdAndClub(newRefeed.getFeedId(), club)).isPresent();
    }

    private Feed saveFeedWithImage(Club club, User user, String content, String img) {
        Feed f = Feed.builder()
                .content(content)
                .club(club)
                .user(user)
                .build();
        f.getFeedImages().add(FeedImage.builder().feedImage(img).feed(f).build()); // 이미지 필수
        return feedRepository.save(f);
    }

    @DisplayName("N명의 서로 다른 유저가 동시에 좋아요 시도 → 각 사용자당 1개씩만 생성된다")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_like_manyUsers_eachGetsOne_withoutThreadLocal() throws Exception {
        // ---- 준비: 별도 트랜잭션으로 클럽/유저/피드 커밋 ----
        record Prepared(Long clubId, Long feedId, List<User> users) {}
        Prepared prepared = txTemplate.execute(status -> {
            // 테스트 전용 클럽 & 유저 N명 추가 가입
            Interest ex = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());
            Club club = clubRepository.save(Club.builder()
                    .name("서울 축구 클럽 - 동시성")
                    .description("concurrency")
                    .userLimit(500)
                    .city("서울").district("강남구")
                    .interest(ex)
                    .clubImage("soccer.jpg")
                    .build());

            int N = 120; // 동시 사용자 수
            List<User> users = new ArrayList<>(N);
            for (int i = 0; i < N; i++) {
                users.add(User.builder()
                        .kakaoId(30000L + i)
                        .nickname("U" + i)
                        .status(Status.ACTIVE)
                        .gender(i % 2 == 0 ? Gender.MALE : Gender.FEMALE)
                        .birth(LocalDate.of(1990, 1, 1))
                        .city("서울").district("강남구")
                        .build());
            }
            userRepository.saveAll(users);
            userClubRepository.saveAll(users.stream()
                    .map(u -> UserClub.builder().user(u).club(club).clubRole(ClubRole.MEMBER).build())
                    .toList());

            // 피드 1개 생성(작성자: 첫 번째 유저)
            when(userService.getCurrentUser()).thenReturn(users.get(0));
            feedService.createFeed(club.getClubId(),
                    FeedRequestDto.builder().feedUrls(List.of("x.jpg")).content("c").build());

            Long feedId = feedRepository.findAll().getLast().getFeedId();
            return new Prepared(club.getClubId(), feedId, users);
        });

        Long clubId = prepared.clubId();
        Long feedId = prepared.feedId();
        List<User> users = prepared.users();

        ConcurrentLinkedQueue<User> queue = new ConcurrentLinkedQueue<>(users);
        reset(userService);
        when(userService.getCurrentUser()).thenAnswer(inv -> {
            User u = queue.poll();
            // 만약 스레드 수 > 유저 수로 오면 마지막 사용자를 재사용
            return (u != null) ? u : users.get(users.size() - 1);
        });

        // ---- 동시 실행 ----
        int threads = users.size();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 32));
        List<Callable<Boolean>> tasks = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startGate.await(); // 동시에 시작
                return feedService.toggleLike(clubId, feedId);
            });
        }

        startGate.countDown();
        List<Future<Boolean>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);


        // ---- 검증 ----
        // 좋아요 행 수
        Long likeRows = feedLikeRepository.countByFeed_FeedId(feedId);

        // ON으로 끝난 Future의 수(참고)
        futures.stream().filter(f -> {
            try {
                return Boolean.TRUE.equals(f.get());
            } catch (Exception e) {
                return false;
            }
        }).count();

        // 각 사용자당 최대 1개씩만 생성 → likeRows == 사용자 수
        assertThat(likeRows).isEqualTo(users.size());
    }

    @DisplayName("N명의 서로 다른 유저가 동시에 좋아요 시도 → 각 사용자당 1개만 생성")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_like_manyUsers_withCallableClass() throws Exception {
        // --- 준비/커밋(생략된 부분은 너의 기존 코드 그대로) ---
        record Prepared(Long clubId, Long feedId, List<User> users) {}
        Prepared p = txTemplate.execute(status -> {
            Interest ex = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());
            Club club = clubRepository.save(Club.builder()
                    .name("동시성-Callable")
                    .description("c").userLimit(1000)
                    .city("서울").district("강남구")
                    .interest(ex).clubImage("c.jpg").build());

            int N = 100000;
            List<User> users = new ArrayList<>(N);
            for (int i = 0; i < N; i++) {
                users.add(userRepository.save(User.builder()
                        .kakaoId(80000L + i).nickname("u"+i)
                        .status(Status.ACTIVE).gender(Gender.MALE)
                        .birth(LocalDate.of(1990,1,1)).city("서울").district("강남구").build()));
            }
            userClubRepository.saveAll(users.stream()
                    .map(u -> UserClub.builder().user(u).club(club).clubRole(ClubRole.MEMBER).build())
                    .toList());

            when(userService.getCurrentUser()).thenReturn(users.getFirst());
            feedService.createFeed(club.getClubId(),
                    FeedRequestDto.builder().feedUrls(List.of("a.jpg")).content("c").build());
            Long feedId = feedRepository.findAll().getLast().getFeedId();
            return new Prepared(club.getClubId(), feedId, users);
        });

        // ---- 호출마다 서로 다른 유저 반환(Queue 기반); ThreadLocal 불사용 ----
        ConcurrentLinkedQueue<User> queue = new ConcurrentLinkedQueue<>(p.users());
        reset(userService);
        when(userService.getCurrentUser()).thenAnswer(inv -> {
            User u = queue.poll();
            return (u != null) ? u : p.users().getLast();
        });

        // ---- 실행 ----
        int threads = p.users().size();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 32));
        List<Future<Boolean>> futures = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(new ToggleLikeTask(
                    startGate, p.clubId(), p.feedId(), txTemplate, feedService
            )));
        }

        startGate.countDown();
        for (Future<Boolean> f : futures) f.get(); // 예외 확인
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // ---- 검증(새 트랜잭션 + 1차 캐시 클리어) ----
        Long likeRows = txTemplate.execute(s -> {
            em.clear();
            return feedLikeRepository.countByFeed_FeedId(p.feedId());
        });
        System.out.println("likeRows = " + likeRows);
        assertThat(likeRows).isEqualTo(p.users().size());

        // (선택) likeCount 필드 사용 시
        txTemplate.execute(s -> {
            em.clear();
            Feed feed = feedRepository.findById(p.feedId()).orElseThrow();
            assertThat(feed.getLikeCount()).isEqualTo(p.users().size());
            return null;
        });
    }
    // 1) 동시 실행용 태스크: 각 스레드가 자기 트랜잭션에서 toggleLike 수행
    static class ToggleLikeTask implements Callable<Boolean> {
        private final CountDownLatch startGate;
        private final Long clubId;
        private final Long feedId;
        private final TransactionTemplate txTemplate;
        private final FeedService feedService;

        ToggleLikeTask(CountDownLatch startGate,
                       Long clubId, Long feedId,
                       TransactionTemplate txTemplate,
                       FeedService feedService) {
            this.startGate = startGate;
            this.clubId = clubId;
            this.feedId = feedId;
            this.txTemplate = txTemplate;
            this.feedService = feedService;
        }

        @Override
        public Boolean call() throws Exception {
            startGate.await(); // 동시에 출발
            // 각 스레드별 "독립 트랜잭션"에서 실행
            return txTemplate.execute(status -> feedService.toggleLike(clubId, feedId));
        }
    }



}

