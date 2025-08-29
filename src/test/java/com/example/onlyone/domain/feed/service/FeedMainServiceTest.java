package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.RefeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.entity.*;
import com.example.onlyone.domain.feed.repository.FeedCommentRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class FeedMainServiceTest {

    @Autowired
    private FeedMainService feedMainService;

    @Autowired private UserRepository userRepository;
    @Autowired private ClubRepository clubRepository;
    @Autowired private UserClubRepository userClubRepository;
    @Autowired private InterestRepository interestRepository;
    @Autowired private FeedRepository feedRepository;
    @Autowired private FeedLikeRepository feedLikeRepository;
    @Autowired private FeedCommentRepository feedCommentRepository;

    @MockitoBean private UserService userService; // 현재 사용자 목킹
    @MockitoBean private NotificationService notificationService; // 리피드 알림 목킹


    @Autowired private EntityManager em;

    private User u1;  // 현재 사용자
    private User u2;
    private User u3;
    private User u4;

    private Club clubA; // u1이 가입
    private Club clubB; // u1이 가입
    private Club clubC; // u2가 추가로 가입(친구의 클럽)
    private Club clubD; // u3가 추가로 가입(친구의 클럽)
    private Club clubE; // 아무도 연관 없는, 접근 불가 클럽

    private Pageable sortedPageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());

    @BeforeEach
    void setUp() {
        // 관심사 하나만 만들어도 충분
        Interest exercise = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());

        // 사용자
        u1 = userRepository.save(User.builder()
                .kakaoId(100L).nickname("u1").status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1990,1,1)).city("서울").district("강남").build());
        u2 = userRepository.save(User.builder()
                .kakaoId(101L).nickname("u2").status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1991,1,1)).city("서울").district("강남").build());
        u3 = userRepository.save(User.builder()
                .kakaoId(102L).nickname("u3").status(Status.ACTIVE).gender(Gender.FEMALE)
                .birth(LocalDate.of(1992,1,1)).city("서울").district("강남").build());
        u4 = userRepository.save(User.builder()
                .kakaoId(103L).nickname("u4").status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1993,1,1)).city("서울").district("강남").build());

        // 클럽들
        clubA = clubRepository.save(Club.builder()
                .name("A").description("A").userLimit(10).city("서울").district("A").interest(exercise).clubImage("a.jpg").build());
        clubB = clubRepository.save(Club.builder()
                .name("B").description("B").userLimit(10).city("서울").district("B").interest(exercise).clubImage("b.jpg").build());
        clubC = clubRepository.save(Club.builder()
                .name("C").description("C").userLimit(10).city("서울").district("C").interest(exercise).clubImage("c.jpg").build());
        clubD = clubRepository.save(Club.builder()
                .name("D").description("D").userLimit(10).city("서울").district("D").interest(exercise).clubImage("d.jpg").build());
        clubE = clubRepository.save(Club.builder()
                .name("E").description("E").userLimit(10).city("서울").district("E").interest(exercise).clubImage("e.jpg").build());

        // 가입 관계
        // u1: A,B 에 가입
        userClubRepository.save(UserClub.builder().user(u1).club(clubA).clubRole(ClubRole.MEMBER).build());
        userClubRepository.save(UserClub.builder().user(u1).club(clubB).clubRole(ClubRole.MEMBER).build());
        // 같은 클럽 멤버(u2,u3)
        userClubRepository.save(UserClub.builder().user(u2).club(clubA).clubRole(ClubRole.MEMBER).build());
        userClubRepository.save(UserClub.builder().user(u2).club(clubC).clubRole(ClubRole.MEMBER).build());
        // 친구들이 추가로 가입한 클럽(C,D)
        userClubRepository.save(UserClub.builder().user(u3).club(clubB).clubRole(ClubRole.MEMBER).build());
        userClubRepository.save(UserClub.builder().user(u3).club(clubD).clubRole(ClubRole.MEMBER).build());
        // clubE는 u4만 가입
        userClubRepository.save(UserClub.builder().user(u4).club(clubE).clubRole(ClubRole.MEMBER).build());

    }

    private Feed saveFeed(Club club, User author, String content, String... imageUrls) {
        Feed f = Feed.builder()
                .club(club)
                .user(author)
                .content(content)
                .build();
        for (String url : imageUrls) {
            f.getFeedImages().add(
                    FeedImage.builder()
                            .feedImage(url)
                            .feed(f)
                            .build()
            );
        }
        return feedRepository.save(f);
    }

    // ★ 리피드 저장 헬퍼
    private Feed saveRefeed(Feed parent, Club targetClub, User author, String content) {
        Long rootId = (parent.getRootFeedId() != null) ? parent.getRootFeedId() : parent.getFeedId();
        Feed rf = Feed.builder()
                .content(content)
                .feedType(FeedType.REFEED)
                .parentFeedId(parent.getFeedId())
                .rootFeedId(rootId)
                .club(targetClub)
                .user(author)
                .build();
        return feedRepository.save(rf);
    }

    private static void tinySleep() {
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
    }

    private RefeedRequestDto makeRequestDto(String content) {
        return RefeedRequestDto.builder().content(content).build();
    }


    @Test
    @DisplayName("현재 사용자 기준 접근 가능한 클럽 집합이 정확히 계산되는가?")
    void personalFeed_filtersAccessibleClubs() {
        // given: 각 클럽에 1개씩 피드 생성
        when(userService.getCurrentUser()).thenReturn(u1);

        saveFeed(clubA, u2, "A1", "a1.jpg");
        saveFeed(clubB, u3, "B1", "b1.jpg");
        saveFeed(clubC, u2, "C1", "c1.jpg"); // 친구(u2)로 인해 접근 가능
        saveFeed(clubD, u3, "D1", "d1.jpg"); // 친구(u3)로 인해 접근 가능
        saveFeed(clubE, u4, "E1", "e1.jpg");

        em.flush(); em.clear();

        // when
        List<FeedOverviewDto> result = feedMainService.getPersonalFeed(sortedPageable);

        // then: clubId만 뽑아 비교
        assertThat(result)
                .extracting(FeedOverviewDto::getClubId)
                .containsExactlyInAnyOrder(
                        clubA.getClubId(), clubB.getClubId(), clubC.getClubId(), clubD.getClubId()
                )
                .doesNotContain(clubE.getClubId());
    }

    @Test
    @DisplayName("작성 시간 기준 최신순으로 피드가 조회되는가?")
    void personalFeed_sortedByCreatedAtDesc() throws Exception {
        // given: 접근 가능한 클럽들 중 3개의 피드 생성(시간 간격을 조금 둠)
        when(userService.getCurrentUser()).thenReturn(u1);
        Feed f1 = saveFeed(clubA, u2, "first", "a.jpg");
        Thread.sleep(5);
        Feed f2 = saveFeed(clubC, u2, "second", "c.jpg");
        Thread.sleep(5);
        Feed f3 = saveFeed(clubB, u3, "third", "b.jpg");

        em.flush(); em.clear();

        // when
        List<FeedOverviewDto> result = feedMainService.getPersonalFeed(sortedPageable);

        // then: 최신순 -> f3, f2, f1
        assertThat(result)
                .extracting(FeedOverviewDto::getFeedId)
                .containsExactly(f3.getFeedId(), f2.getFeedId(), f1.getFeedId());
    }

    @Test
    @DisplayName("페이징(page, limit)을 반영하여 최대 limit개만 반환된다")
    void personalFeed_respectsLimit() {
        when(userService.getCurrentUser()).thenReturn(u1);
        Pageable pageable = PageRequest.of(0, 3, Sort.by("createdAt").descending());

        // 접근 가능한 네 클럽(A,B,C,D)에 5개 작성(최신이 먼저 오도록 시간차)
        Feed g1 = saveFeed(clubA, u2, "g1", "a1.jpg"); tinySleep();
        Feed g2 = saveFeed(clubB, u3, "g2", "b1.jpg"); tinySleep();
        Feed g3 = saveFeed(clubC, u2, "g3", "c1.jpg"); tinySleep();
        Feed g4 = saveFeed(clubD, u3, "g4", "d1.jpg"); tinySleep();
        Feed g5 = saveFeed(clubA, u1, "g5", "a2.jpg"); // 최신

        em.flush(); em.clear();

        List<FeedOverviewDto> res = feedMainService.getPersonalFeed(pageable);

        assertThat(res).hasSize(3);
        assertThat(res.stream().map(FeedOverviewDto::getFeedId).toList())
                .containsExactly(g5.getFeedId(), g4.getFeedId(), g3.getFeedId());
    }

    @Test
    @DisplayName("현재 사용자의 좋아요 여부(isLiked) 및 소유 여부(isMine)가 정상적으로 기입돼 있다.")
    void personalFeed_flagsAndRepostCount_areAccurate() {
        when(userService.getCurrentUser()).thenReturn(u1);

        // 부모 피드(작성자 u2, clubA)
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // 직계 리피드 2개(둘 다 parentFeedId = parent.id) — 작성자는 clubA 가입자인 u1, u2
        saveRefeed(parent, clubA, u1, "r1");
        saveRefeed(parent, clubA, u2, "r2");

        // 내 피드(작성자 u1)
        Feed mine = saveFeed(clubA, u1, "mine", "m.jpg");

        // 좋아요: u1이 parent에 좋아요
        feedLikeRepository.save(FeedLike.builder().feed(parent).user(u1).build());

        em.flush();
        em.clear();

        List<FeedOverviewDto> list = feedMainService.getPersonalFeed(sortedPageable);
        Map<Long, FeedOverviewDto> byId = list.stream()
                .collect(Collectors.toMap(FeedOverviewDto::getFeedId, x -> x));

        FeedOverviewDto p = byId.get(parent.getFeedId());
        FeedOverviewDto m = byId.get(mine.getFeedId());

        assertThat(p).isNotNull();
        assertThat(m).isNotNull();

        // parent: 내가 좋아요(true), 내 글 아님(false)
        assertThat(p.isLiked()).isTrue();
        assertThat(p.isFeedMine()).isFalse();

        // mine: 좋아요 안눌렀음(false), 내 글(true)
        assertThat(m.isLiked()).isFalse();
        assertThat(m.isFeedMine()).isTrue();
    }

    @Test
    @DisplayName("이미지 URL 리스트가 저장 순서대로 반환된다")
    void personalFeed_imageOrder_preserved() {
        when(userService.getCurrentUser()).thenReturn(u1);

        // 한 번에 content + 이미지들 삽입
        Feed f = saveFeed(clubA, u1, "with-images",
                "1.jpg", "2.jpg", "10.jpg", "a.jpg");

        em.flush(); em.clear();

        List<FeedOverviewDto> list = feedMainService.getPersonalFeed(sortedPageable);
        FeedOverviewDto dto = list.stream()
                .filter(d -> d.getFeedId().equals(f.getFeedId()))
                .findFirst().orElseThrow();

        assertThat(dto.getImageUrls())
                .containsExactly("1.jpg", "2.jpg", "10.jpg", "a.jpg");
    }

    @Test
    @DisplayName("해당 피드의 좋아요/댓글/리피드 개수가 정확한가")
    void personalFeed_counts_like_comment_repost() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        // 원글(이미지 1장) — clubA, 작성자 u2
        Feed original = saveFeed(clubA, u2, "orig", "orig.jpg");

        // 좋아요: 3개(u1, u2, u3)
        feedLikeRepository.save(FeedLike.builder().feed(original).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(original).user(u2).build());
        feedLikeRepository.save(FeedLike.builder().feed(original).user(u3).build());

        // 댓글: 2개(u1, u2)
        feedCommentRepository.save(FeedComment.builder().feed(original).user(u1).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(original).user(u2).content("c2").build());

        // 리피드(직접 자식) 2개 — parentFeedId = original.id
        // clubB/u3, clubC/u2 로 하나씩
        saveRefeed(original, clubB, u3, "rf-1");
        saveRefeed(original, clubC, u2, "rf-2");

        em.flush(); em.clear();

        // when
        List<FeedOverviewDto> list = feedMainService.getPersonalFeed(sortedPageable);

        // then: 원글 DTO 찾아서 개수 검증
        FeedOverviewDto dto = list.stream()
                .filter(d -> d.getFeedId().equals(original.getFeedId()))
                .findFirst().orElseThrow();

        assertThat(dto.getLikeCount()).isEqualTo(3);
        assertThat(dto.getCommentCount()).isEqualTo(2);
        assertThat(dto.getRepostCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("점수 큰 순으로 정렬된다 (비슷한 시각에 생성된 피드들이 점수 차이에 의해 정렬되는지 체크)")
    void popularFeed_ordersByScore_whenAgesSimilar_withRefeed() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        // refeed의 parent가 될 원글 하나
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // f1: 일반글 점수 = 3  (좋아요 3)
        Feed f1 = saveFeed(clubA, u2, "f1", "1.jpg");
        feedLikeRepository.save(FeedLike.builder().feed(f1).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(f1).user(u2).build());
        feedLikeRepository.save(FeedLike.builder().feed(f1).user(u3).build());

        // f2: 리피드 점수 = 2(좋아요) + 2*1(댓글1개) + 2(리피드) = 6  ← 최상
        Feed f2 = saveRefeed(parent, clubB, u3, "f2-refeed");
        feedLikeRepository.save(FeedLike.builder().feed(f2).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(f2).user(u2).build());
        feedCommentRepository.save(FeedComment.builder().feed(f2).user(u1).content("c").build());

        // f3: 일반글 점수 = 1 + 2*2 = 5  ← 중간
        Feed f3 = saveFeed(clubC, u2, "f3", "3.jpg");
        feedLikeRepository.save(FeedLike.builder().feed(f3).user(u1).build());
        feedCommentRepository.save(FeedComment.builder().feed(f3).user(u2).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(f3).user(u3).content("c2").build());

        em.flush(); em.clear();

        // when
        List<FeedOverviewDto> list = feedMainService.getPopularFeed(PageRequest.of(0, 10));

        // then: f2(6) > f3(5) > f1(3) 순서가 유지되는지 (중간에 다른 글이 있어도 상대적 순서만 보면 됨)
        assertThat(list.stream().map(FeedOverviewDto::getFeedId).toList())
                .containsSubsequence(f2.getFeedId(), f3.getFeedId(), f1.getFeedId());
    }

    @Test
    @DisplayName("오래된 고점수를 < 최신 저점수 (시간 패널티가 잘 적용되는 지 체크)")
    void popularFeed_recencyBeatsOlderEngagement_withRefeed() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        // refeed parent
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // fOld: 리피드 + 높은 점수  → 2(좋아요2) + 2*2(댓글2) + 2(리피드) = 8  하지만 48시간 전으로 백데이트
        Feed fOld = saveRefeed(parent, clubA, u2, "old-refeed");
        feedLikeRepository.save(FeedLike.builder().feed(fOld).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(fOld).user(u2).build());
        feedCommentRepository.save(FeedComment.builder().feed(fOld).user(u3).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(fOld).user(u1).content("c2").build());

        // fNew: 최신 저점수  → 3(좋아요3)
        Feed fNew = saveFeed(clubB, u3, "new", "n.jpg");
        feedLikeRepository.save(FeedLike.builder().feed(fNew).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(fNew).user(u2).build());
        feedLikeRepository.save(FeedLike.builder().feed(fNew).user(u3).build());

        em.flush();

        // fOld를 48시간 전으로 (시간 패널티 = 48/12 = 4 만큼 깎임 → log(8)-4 < log(3) 라서 fNew가 앞서야 함)
        em.createNativeQuery("UPDATE feed SET created_at = DATE_SUB(NOW(), INTERVAL 48 HOUR) WHERE feed_id = ?")
                .setParameter(1, fOld.getFeedId())
                .executeUpdate();
        em.clear();

        // when
        List<FeedOverviewDto> list = feedMainService.getPopularFeed(PageRequest.of(0, 10));

        // then
        List<Long> ids = list.stream().map(FeedOverviewDto::getFeedId).toList();
        int idxNew = ids.indexOf(fNew.getFeedId());
        int idxOld = ids.indexOf(fOld.getFeedId());

        assertThat(idxNew).isGreaterThanOrEqualTo(0);
        assertThat(idxOld).isGreaterThanOrEqualTo(0);
        assertThat(idxNew).isLessThan(idxOld);
    }

    @Test
    @DisplayName("리피드 생성: 미가입자는 해당 모임에 리피드 생성 시 예외(CLUB_NOT_JOIN)")
    void createRefeed_nonMember_throwsClubNotJoin() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        // 원본 피드(어느 클럽이든 상관 없음) — clubA에 u2가 작성
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // u1은 clubC에 가입 안 되어 있음 → clubC로 리피드 시도하면 예외
        Long targetClubId = clubC.getClubId();

        // when & then
        assertThatThrownBy(() ->
                feedMainService.createRefeed(parent.getFeedId(), targetClubId, makeRequestDto("hello"))
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_NOT_JOIN);

        // 알림은 보내지지 않아야 함
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("리피드 생성: 동일 사용자·동일 클럽·동일 원본 중복 리피드 시 예외(DUPLICATE_REFEED)")
    void createRefeed_duplicate_throwsDuplicateRefeed() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // 1) 첫 리피드 정상 생성
        feedMainService.createRefeed(parent.getFeedId(), clubA.getClubId(), makeRequestDto("first"));

        // 2) 동일 조합 재시도 → 예외
        Throwable t = catchThrowable(() ->
                feedMainService.createRefeed(parent.getFeedId(), clubA.getClubId(), makeRequestDto("second"))
        );
        assertThat(t)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_REFEED);

        em.clear();
        // 방어 검증: 현재 트랜잭션 관점에서 리피드 1개만 존재
        long cnt = feedRepository.findAll().stream()
                .filter(f -> f.getFeedType() == FeedType.REFEED)
                .filter(f -> Objects.equals(f.getParentFeedId(), parent.getFeedId()))
                .filter(f -> Objects.equals(f.getUser().getUserId(), u1.getUserId()))
                .filter(f -> Objects.equals(f.getClub().getClubId(), clubA.getClubId()))
                .count();
        assertThat(cnt).isEqualTo(1L);
    }

    @Test
    @DisplayName("리피드 삭제 후 동일 피드로 재리피드가 가능하다")
    void createRefeed_afterSoftDelete_allowsRepost_again_aliveOnly() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // 1) 첫 리피드 정상 생성
        feedMainService.createRefeed(parent.getFeedId(), clubA.getClubId(), makeRequestDto("first"));
        em.flush(); em.clear();

        // 현재 살아있는(삭제되지 않은) 내 리피드 하나 찾아 id 확보
        Long firstRefeedId = feedRepository.findAll().stream()
                .filter(f -> f.getFeedType() == FeedType.REFEED)
                .filter(f -> Objects.equals(f.getParentFeedId(), parent.getFeedId()))
                .filter(f -> Objects.equals(f.getUser().getUserId(), u1.getUserId()))
                .filter(f -> Objects.equals(f.getClub().getClubId(), clubA.getClubId()))
                .map(Feed::getFeedId)
                .findFirst()
                .orElseThrow();

        int updated = feedRepository.softDeleteById(firstRefeedId);
        assertThat(updated).isEqualTo(1);
        em.clear();

        // 3) 동일 조합 재리피드 → 예외 없이 성공해야 함
        feedMainService.createRefeed(parent.getFeedId(), clubA.getClubId(), makeRequestDto("second"));
        em.flush(); em.clear();

        // then: @Where에 의해 '살아있는' 리피드만 보임 → 정확히 1개여야 하고, 내용은 second
        List<Feed> alive = feedRepository.findAll().stream()
                .filter(f -> f.getFeedType() == FeedType.REFEED)
                .filter(f -> Objects.equals(f.getParentFeedId(), parent.getFeedId()))
                .filter(f -> Objects.equals(f.getUser().getUserId(), u1.getUserId()))
                .filter(f -> Objects.equals(f.getClub().getClubId(), clubA.getClubId()))
                .toList();

        assertThat(alive).hasSize(1);
        assertThat(alive.get(0).getContent()).isEqualTo("second");
    }


    @Test
    @DisplayName("본인 피드를 리피드 할 수 있다.")
    void createRefeed_canRepostOwnFeed_andNoNotification() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);

        // 내 원본글
        Feed mine = saveFeed(clubA, u1, "mine", "m.jpg");

        // when: 같은 클럽으로 자기 글 리피드
        feedMainService.createRefeed(mine.getFeedId(), clubA.getClubId(), makeRequestDto("self-quote"));
        em.flush(); em.clear();

        // then: 생성된 리피드가 정상 속성으로 저장되었는지
        Feed selfRefeed = feedRepository.findAll().stream()
                .filter(f -> f.getFeedType() == FeedType.REFEED)
                .filter(f -> Objects.equals(f.getParentFeedId(), mine.getFeedId()))
                .filter(f -> Objects.equals(f.getUser().getUserId(), u1.getUserId()))
                .filter(f -> Objects.equals(f.getClub().getClubId(), clubA.getClubId()))
                .findFirst()
                .orElseThrow();

        assertThat(selfRefeed.getFeedType()).isEqualTo(FeedType.REFEED);
        assertThat(selfRefeed.getParentFeedId()).isEqualTo(mine.getFeedId());
        assertThat(selfRefeed.getRootFeedId()).isEqualTo(mine.getFeedId()); // parent가 루트
        assertThat(selfRefeed.isDeleted()).isFalse();

        // 자기 글 리피드이므로 알림은 발송되지 않아야 함
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("댓글 리스트: 작성 시각이 오름차순으로 정렬되어 반환된다")
    void getCommentList_sortedByCreatedAtAsc() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);
        Feed feed = saveFeed(clubA, u2, "has comments", "img.jpg");

        // 작성 시간 간격 확보
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u2).content("c1").build());
        tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u1).content("c2").build());
        tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u3).content("c3").build());

        em.flush(); em.clear();

        // when
        List<FeedCommentResponseDto> result =
                feedMainService.getCommentList(feed.getFeedId(), PageRequest.of(0, 10));

        // then: 오름차순(c1 -> c2 -> c3)
        assertThat(result).extracting(FeedCommentResponseDto::getContent)
                .containsExactly("c1", "c2", "c3");
    }

    @Test
    @DisplayName("댓글 리스트: 페이징을 반영하여 최대 limit개만 반환된다")
    void getCommentList_respectsLimit() {
        // given
        when(userService.getCurrentUser()).thenReturn(u1);
        Feed feed = saveFeed(clubA, u2, "has many comments", "img.jpg");

        // 5개 생성(오름차순 시간차)
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u2).content("c1").build());
        tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u1).content("c2").build());
        tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u3).content("c3").build());
        tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u2).content("c4").build());
        tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u1).content("c5").build());

        em.flush(); em.clear();

        // when: limit=3
        Pageable pageable = PageRequest.of(0, 3); // 정렬은 메서드명 OrderByCreatedAt(ASC)로 처리됨
        List<FeedCommentResponseDto> result =
                feedMainService.getCommentList(feed.getFeedId(), pageable);

        // then: 개수=3, 그리고 오름차순의 앞 3개(c1,c2,c3)
        assertThat(result).hasSize(3);
        assertThat(result).extracting(FeedCommentResponseDto::getContent)
                .containsExactly("c1", "c2", "c3");
    }

}