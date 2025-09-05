package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.entity.FeedImage;
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
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class FeedControllerIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Autowired InterestRepository interestRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserClubRepository userClubRepository;
    @Autowired FeedRepository feedRepository;
    @Autowired FeedLikeRepository feedLikeRepository;
    @Autowired FeedCommentRepository feedCommentRepository;
    @Autowired EntityManager em;

    @MockitoBean UserService userService;           // 현재 유저 주입
    @MockitoBean NotificationService notificationService;

    @Autowired TransactionTemplate tx;

    private final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    private Interest ex;
    private Club club;
    private User leader;
    private User member;
    private User outsider;
    private List<User> likeUsers;

    @BeforeEach
    void setUp() {
        when(userService.getCurrentUser()).thenAnswer(inv -> {
            User u = CURRENT.get();
            if (u == null) throw new IllegalStateException("CURRENT user not set");
            return u;
        });

        ex = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());

        leader = saveUser(11111L, "leader", Gender.MALE, "서울", "강남");
        member = saveUser(22222L, "member", Gender.FEMALE, "서울", "강남");
        outsider = saveUser(33333L, "outsider", Gender.MALE, "부산", "해운대");

        club = clubRepository.save(Club.builder()
                .name("축구 클럽").description("함께 차요")
                .userLimit(100).city("서울").district("강남")
                .interest(ex).clubImage("c.jpg").build());

        userClubRepository.save(UserClub.builder().user(leader).club(club).clubRole(ClubRole.LEADER).build());
        userClubRepository.save(UserClub.builder().user(member).club(club).clubRole(ClubRole.MEMBER).build());

        likeUsers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User u = saveUser(90000L + i, "u" + i, Gender.MALE, "서울", "강남");
            userClubRepository.save(UserClub.builder().user(u).club(club).clubRole(ClubRole.MEMBER).build());
            likeUsers.add(u);
        }
        em.flush(); em.clear();
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(s -> {
            // MySQL이면 FK 체크를 잠깐 끕니다 (선택)
            em.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();

            em.createNativeQuery("DELETE FROM feed_like").executeUpdate();
            em.createNativeQuery("DELETE FROM feed_comment").executeUpdate();
            em.createNativeQuery("DELETE FROM feed_image").executeUpdate();
            em.createNativeQuery("DELETE FROM feed").executeUpdate();

            em.createNativeQuery("DELETE FROM user_club").executeUpdate();
            em.createNativeQuery("DELETE FROM club_like").executeUpdate(); // 있으면
            em.createNativeQuery("DELETE FROM club").executeUpdate();

            em.createNativeQuery("DELETE FROM user").executeUpdate();
            em.createNativeQuery("DELETE FROM interest").executeUpdate();

            em.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate();
        });
    }


    private User saveUser(long kakaoId, String nickname, Gender g, String city, String district) {
        return userRepository.save(User.builder()
                .kakaoId(kakaoId).nickname(nickname).status(Status.ACTIVE).gender(g)
                .birth(LocalDate.of(1990,1,1)).city(city).district(district).build());
    }

    private String json(Object o) throws Exception { return om.writeValueAsString(o); }
    private Map<String, Object> feedReq(List<String> urls, String content) {
        return Map.of("feedUrls", urls, "content", content);
    }
    private JsonNode dataNode(String content) throws Exception {
        JsonNode root = om.readTree(content);
        return root.has("data") ? root.get("data") : root;
    }
    private Long latestFeedId() { return feedRepository.findAll().getLast().getFeedId(); }


    // -------- 기본 플로우 --------

    @Test
    @DisplayName("모임 가입자는 피드를 생성할 수 있다 (201, success=true)")
    void createFeed_member_created201() throws Exception {
        CURRENT.set(member);

        mvc.perform(post("/clubs/{clubId}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("a.jpg","b.jpg"), "운동!"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(feedRepository.count()).isEqualTo(1);
        Feed f = feedRepository.findAll().getFirst();
        assertThat(f.getFeedImages()).extracting(FeedImage::getFeedImage)
                .containsExactly("a.jpg","b.jpg");
    }

    @Test
    @DisplayName("미가입자는 피드 생성 시 4xx 에러")
    void createFeed_nonMember_error() throws Exception {
        CURRENT.set(outsider);

        mvc.perform(post("/clubs/{clubId}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("x.jpg"), "안되나?"))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(content().string(containsString("CLUB_NOT_JOIN")));
    }

    @Test
    @DisplayName("피드 상세 조회 (OK, 래핑)")
    void getFeedDetail_ok() throws Exception {
        CURRENT.set(leader);
        mvc.perform(post("/clubs/{clubId}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("d.jpg"), "detail"))))
                .andExpect(status().isCreated());
        Long feedId = latestFeedId();

        CURRENT.set(member);
        String body = mvc.perform(get("/clubs/{clubId}/feeds/{feedId}", club.getClubId(), feedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = dataNode(body);
        assertThat(data.get("feedId").asLong()).isEqualTo(feedId);
        assertThat(data.get("imageUrls").get(0).asText()).isEqualTo("d.jpg");
    }

    @Test
    @DisplayName("모임 피드 목록 조회: Page 래핑")
    void list_byClub_returnsPage() throws Exception {
        CURRENT.set(leader);
        mvc.perform(post("/clubs/{id}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("a.jpg"), "A"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/clubs/{id}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("b.jpg"), "B"))))
                .andExpect(status().isCreated());

        CURRENT.set(member);
        mvc.perform(get("/clubs/{id}/feeds?limit=10&page=0", club.getClubId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].feedId").exists())
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").exists());
    }

    @Test
    @DisplayName("좋아요 토글: 200 → 204, 집계 반영")
    void toggleLike_roundTrip() throws Exception {
        CURRENT.set(leader);
        mvc.perform(post("/clubs/{clubId}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("x.jpg"), "hello"))))
                .andExpect(status().isCreated());
        Long feedId = latestFeedId();

        CURRENT.set(member);
        mvc.perform(put("/clubs/{clubId}/feeds/{feedId}/likes", club.getClubId(), feedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(put("/clubs/{clubId}/feeds/{feedId}/likes", club.getClubId(), feedId))
                .andExpect(status().isNoContent());

        assertThat(feedLikeRepository.countByFeed_FeedId(feedId)).isZero();
        assertThat(feedRepository.findById(feedId).orElseThrow().getLikeCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("댓글 생성/삭제: 컨트롤러 경유")
    void comment_create_delete() throws Exception {
        CURRENT.set(leader);
        mvc.perform(post("/clubs/{clubId}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("a.jpg"), "cmt"))))
                .andExpect(status().isCreated());

        Long feedId = latestFeedId();

        em.flush();
        em.clear();

        CURRENT.set(member);
        mvc.perform(post("/clubs/{clubId}/feeds/{fid}/comments", club.getClubId(), feedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(FeedCommentRequestDto.builder().content("안녕!").build())))
                .andExpect(status().isCreated());

        em.flush();
        em.clear();

        // 이제 상세 조회는 새 영속성 컨텍스트에서 DB를 다시 보게 됨
        mvc.perform(get("/clubs/{clubId}/feeds/{fid}", club.getClubId(), feedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentCount").value(1));
        em.flush();
        em.clear();

        List<FeedComment> feedComments = feedCommentRepository.findByFeed_FeedId(feedId);
        Long commentId = feedComments.getLast().getFeedCommentId();


        mvc.perform(delete("/clubs/{clubId}/feeds/{fid}/comments/{cid}", club.getClubId(), feedId, commentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        em.flush();
        em.clear();

        assertThat(feedCommentRepository.countByFeed_FeedId(feedId)).isZero();

        mvc.perform(get("/clubs/{clubId}/feeds/{fid}", club.getClubId(), feedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentCount").value(0));
    }



    // -------- 동시성 좋아요 (컨트롤러 경유) --------
    @Test
    @DisplayName("동시에 모두 1회 ON: 응답 200, 행/집계 N")
    void toggleLike_concurrent_on() throws Exception {
        CURRENT.set(leader);
        mvc.perform(post("/clubs/{cid}/feeds", club.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(feedReq(List.of("x.jpg"), "concurrency"))))
                .andExpect(status().isCreated());
        Long feedId = latestFeedId();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        int N = likeUsers.size();
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(N);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (User u : likeUsers) {
            pool.submit(() -> {
                try {
                    start.await();
                    CURRENT.set(u);
                    try {
                        var res = mvc.perform(put("/clubs/{cid}/feeds/{fid}/likes", club.getClubId(), feedId))
                                .andReturn().getResponse();
                        statuses.add(res.getStatus());
                    } finally {
                        CURRENT.remove();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        TestTransaction.start();
        em.clear();
        assertThat(errors).isEmpty();
        assertThat(statuses).hasSize(N).allMatch(s -> s == 200);

        Feed f = feedRepository.findById(feedId).orElseThrow();
        long rows = feedLikeRepository.countByFeed_FeedId(feedId);
        assertThat(rows).isEqualTo(N);
        assertThat(f.getLikeCount()).isEqualTo((long) N);
        TestTransaction.end();
    }

    @Test
    @DisplayName("동시에 모두 OFF: 응답 204, 행/집계 0")
    void toggleLike_concurrent_off() throws Exception {
        // 선행 ON
        toggleLike_concurrent_on();
        TestTransaction.start();
        long feedId = feedRepository.findAll().getLast().getFeedId();
        TestTransaction.end();

        int N = likeUsers.size();
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(N);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (User u : likeUsers) {
            pool.submit(() -> {
                try {
                    start.await();
                    CURRENT.set(u);
                    try {
                        var res = mvc.perform(put("/clubs/{cid}/feeds/{fid}/likes", club.getClubId(), feedId))
                                .andReturn().getResponse();
                        statuses.add(res.getStatus());
                    } finally {
                        CURRENT.remove();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        TestTransaction.start();
        em.clear();
        assertThat(errors).isEmpty();
        assertThat(statuses).hasSize(N).allMatch(s -> s == 204);

        Feed f = feedRepository.findById(feedId).orElseThrow();
        long rows = feedLikeRepository.countByFeed_FeedId(feedId);
        assertThat(rows).isEqualTo(0L);
        assertThat(f.getLikeCount()).isEqualTo(0L);
        TestTransaction.end();
    }
}
