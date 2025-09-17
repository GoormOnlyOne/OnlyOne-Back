package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.OnlyoneApplication;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import com.example.onlyone.domain.feed.entity.FeedImage;
import com.example.onlyone.domain.feed.entity.FeedLike;
import com.example.onlyone.domain.feed.entity.FeedType;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * /feeds 계열(개인피드/인기/댓글/리피드) 컨트롤러 통합 테스트
 */
@SpringBootTest(classes = OnlyoneApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class FeedMainControllerIT {

    private static final String FEEDS = "/feeds";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Autowired EntityManager em;

    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserClubRepository userClubRepository;
    @Autowired InterestRepository interestRepository;
    @Autowired FeedRepository feedRepository;
    @Autowired FeedLikeRepository feedLikeRepository;
    @Autowired FeedCommentRepository feedCommentRepository;

    @MockitoBean UserService userService;
    @MockitoBean NotificationService notificationService;

    User u1; // current
    User u2;
    User u3;
    User u4;

    Club clubA; // u1 가입
    Club clubB; // u1 가입
    Club clubC; // u2 추가 가입(친구 클럽)
    Club clubD; // u3 추가 가입(친구 클럽)
    Club clubE; // 접근 불가

    @BeforeEach
    void setUp() {
        Interest ex = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());

        u1 = userRepository.save(User.builder().kakaoId(80001L).nickname("u1").status(Status.ACTIVE)
                .gender(Gender.MALE).birth(LocalDate.of(1990,1,1)).city("서울").district("강남").build());
        u2 = userRepository.save(User.builder().kakaoId(80002L).nickname("u2").status(Status.ACTIVE)
                .gender(Gender.MALE).birth(LocalDate.of(1991,1,1)).city("서울").district("강남").build());
        u3 = userRepository.save(User.builder().kakaoId(80003L).nickname("u3").status(Status.ACTIVE)
                .gender(Gender.FEMALE).birth(LocalDate.of(1992,1,1)).city("서울").district("강남").build());
        u4 = userRepository.save(User.builder().kakaoId(80004L).nickname("u4").status(Status.ACTIVE)
                .gender(Gender.MALE).birth(LocalDate.of(1993,1,1)).city("서울").district("강남").build());

        given(userService.getCurrentUser()).willReturn(u1);

        clubA = clubRepository.save(Club.builder().name("A").description("A").userLimit(10)
                .city("서울").district("A").interest(ex).clubImage("a.jpg").build());
        clubB = clubRepository.save(Club.builder().name("B").description("B").userLimit(10)
                .city("서울").district("B").interest(ex).clubImage("b.jpg").build());
        clubC = clubRepository.save(Club.builder().name("C").description("C").userLimit(10)
                .city("서울").district("C").interest(ex).clubImage("c.jpg").build());
        clubD = clubRepository.save(Club.builder().name("D").description("D").userLimit(10)
                .city("서울").district("D").interest(ex).clubImage("d.jpg").build());
        clubE = clubRepository.save(Club.builder().name("E").description("E").userLimit(10)
                .city("서울").district("E").interest(ex).clubImage("e.jpg").build());

        // 가입 관계
        userClubRepository.save(UserClub.builder().user(u1).club(clubA).clubRole(ClubRole.MEMBER).build());
        userClubRepository.save(UserClub.builder().user(u1).club(clubB).clubRole(ClubRole.MEMBER).build());

        userClubRepository.save(UserClub.builder().user(u2).club(clubA).clubRole(ClubRole.MEMBER).build());
        userClubRepository.save(UserClub.builder().user(u2).club(clubC).clubRole(ClubRole.MEMBER).build());

        userClubRepository.save(UserClub.builder().user(u3).club(clubB).clubRole(ClubRole.MEMBER).build());
        userClubRepository.save(UserClub.builder().user(u3).club(clubD).clubRole(ClubRole.MEMBER).build());

        userClubRepository.save(UserClub.builder().user(u4).club(clubE).clubRole(ClubRole.MEMBER).build());
    }

    // ---- 헬퍼(데이터 적재는 리포지토리 사용; 이 컨트롤러는 조회 중심) ----
    private Feed saveFeed(Club club, User author, String content, String... imgUrls) {
        Feed f = Feed.builder().club(club).user(author).content(content).build();
        for (String url : imgUrls) {
            f.getFeedImages().add(FeedImage.builder().feedImage(url).feed(f).build());
        }
        return feedRepository.save(f);
    }
    private Feed saveRefeed(Feed parent, Club targetClub, User author, String content) {
        Long rootId = (parent.getRootFeedId() != null) ? parent.getRootFeedId() : parent.getFeedId();
        return feedRepository.save(Feed.builder()
                .content(content)
                .feedType(FeedType.REFEED)
                .parentFeedId(parent.getFeedId())
                .rootFeedId(rootId)
                .club(targetClub)
                .user(author)
                .build());
    }
    private static void tinySleep() { try { Thread.sleep(5); } catch (InterruptedException ignored) {} }
    private JsonNode data(String content) throws Exception {
        JsonNode root = om.readTree(content);
        return root.has("data") ? root.get("data") : root;
    }

    // ---------------- 테스트 ----------------

    @Test
    @DisplayName("개인 피드: 접근 가능한 클럽(A,B + 친구 통해 C,D)만 반환되고 E는 제외")
    void personalFeed_filtersAccessibleClubs() throws Exception {
        // 각 클럽에 1개씩
        saveFeed(clubA, u2, "A1", "a1.jpg");
        saveFeed(clubB, u3, "B1", "b1.jpg");
        saveFeed(clubC, u2, "C1", "c1.jpg");
        saveFeed(clubD, u3, "D1", "d1.jpg");
        saveFeed(clubE, u4, "E1", "e1.jpg");

        em.flush(); em.clear();

        String json = mvc.perform(get(FEEDS).param("page","0").param("limit","20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = data(json);

        var clubIds = arr.findValuesAsText("clubId").stream().map(Long::valueOf).toList();
        assertThat(clubIds).contains(clubA.getClubId(), clubB.getClubId(), clubC.getClubId(), clubD.getClubId());
        assertThat(clubIds).doesNotContain(clubE.getClubId());
    }

    @Test
    @DisplayName("개인 피드: 최신순(createdAt DESC) 정렬")
    void personalFeed_sortedDesc() throws Exception {
        var f1 = saveFeed(clubA, u2, "first", "a.jpg"); tinySleep();
        var f2 = saveFeed(clubC, u2, "second", "c.jpg"); tinySleep();
        var f3 = saveFeed(clubB, u3, "third", "b.jpg");

        em.flush(); em.clear();

        String json = mvc.perform(get(FEEDS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = data(json);

        assertThat(arr.get(0).get("feedId").asLong()).isEqualTo(f3.getFeedId());
        assertThat(arr.get(1).get("feedId").asLong()).isEqualTo(f2.getFeedId());
        assertThat(arr.get(2).get("feedId").asLong()).isEqualTo(f1.getFeedId());
    }

    @Test
    @DisplayName("개인 피드: limit 반영")
    void personalFeed_respectsLimit() throws Exception {
        saveFeed(clubA, u2, "g1", "a1.jpg"); tinySleep();
        saveFeed(clubB, u3, "g2", "b1.jpg"); tinySleep();
        saveFeed(clubC, u2, "g3", "c1.jpg"); tinySleep();
        saveFeed(clubD, u3, "g4", "d1.jpg"); tinySleep();
        saveFeed(clubA, u1, "g5", "a2.jpg");

        em.flush(); em.clear();

        String json = mvc.perform(get(FEEDS).param("page","0").param("limit","3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = data(json);

        assertThat(arr.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("개인 피드: isLiked / isFeedMine / imageUrls 순서 / 카운트 검증")
    void personalFeed_flags_counts_imageOrder() throws Exception {
        // parent + mine
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");
        saveRefeed(parent, clubA, u1, "r1");
        saveRefeed(parent, clubA, u2, "r2");

        Feed mine = saveFeed(clubA, u1, "mine", "1.jpg","2.jpg","10.jpg","a.jpg");

        // 좋아요: u1이 parent에 좋아요
        feedLikeRepository.save(FeedLike.builder().feed(parent).user(u1).build());
        // 댓글 2개
        feedCommentRepository.save(FeedComment.builder().feed(parent).user(u1).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(parent).user(u2).content("c2").build());

        em.flush(); em.clear();

        String json = mvc.perform(get(FEEDS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = data(json);

        // parent
        JsonNode p = null, m = null;
        for (JsonNode n : arr) {
            if (n.get("feedId").asLong() == parent.getFeedId()) p = n;
            if (n.get("feedId").asLong() == mine.getFeedId()) m = n;
        }
        assertThat(p).isNotNull();
        assertThat(m).isNotNull();

        assertThat(p.path("liked").asBoolean()).isTrue();
        assertThat(p.path("feedMine").asBoolean()).isFalse();
        assertThat(p.path("commentCount").asInt()).isEqualTo(2);

        assertThat(m.path("liked").asBoolean()).isFalse();
        assertThat(m.path("feedMine").asBoolean()).isTrue();
        assertThat(m.path("imageUrls").toString())
                .isEqualTo("[\"1.jpg\",\"2.jpg\",\"10.jpg\",\"a.jpg\"]");
    }

    @Test
    @DisplayName("인기 피드: 점수/시간 패널티에 따른 정렬")
    void popularFeed_ordering() throws Exception {
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // f1: 좋아요 3 → 점수 3
        Feed f1 = saveFeed(clubA, u2, "f1", "1.jpg");
        feedLikeRepository.save(FeedLike.builder().feed(f1).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(f1).user(u2).build());
        feedLikeRepository.save(FeedLike.builder().feed(f1).user(u3).build());

        // f2: 리피드(좋아요2 + 댓글1 + 리피드 가산) → 더 높은 점수
        Feed f2 = saveRefeed(parent, clubB, u3, "f2-refeed");
        feedLikeRepository.save(FeedLike.builder().feed(f2).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(f2).user(u2).build());
        feedCommentRepository.save(FeedComment.builder().feed(f2).user(u1).content("c").build());

        // f3: 좋아요1 + 댓글2 → 중간
        Feed f3 = saveFeed(clubC, u2, "f3", "3.jpg");
        feedLikeRepository.save(FeedLike.builder().feed(f3).user(u1).build());
        feedCommentRepository.save(FeedComment.builder().feed(f3).user(u2).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(f3).user(u3).content("c2").build());

        em.flush(); em.clear();

        String json = mvc.perform(get(FEEDS + "/popular").param("page","0").param("limit","10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = data(json).findValues("feedId").stream().map(JsonNode::asLong).toList();

        // 상대 순서만 확인: f2 > f3 > f1
        assertThat(ids).containsSubsequence(f2.getFeedId(), f3.getFeedId(), f1.getFeedId());
    }

    @Test
    @DisplayName("인기 피드: 최신 저점수가 오래된 고점수보다 앞선다(시간 패널티)")
    void popularFeed_recencyBeatsOldHighScore() throws Exception {
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // 오래된 고점수
        Feed fOld = saveRefeed(parent, clubA, u2, "old-refeed");
        feedLikeRepository.save(FeedLike.builder().feed(fOld).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(fOld).user(u2).build());
        feedCommentRepository.save(FeedComment.builder().feed(fOld).user(u3).content("c1").build());
        feedCommentRepository.save(FeedComment.builder().feed(fOld).user(u1).content("c2").build());
        em.flush();
        em.createNativeQuery("UPDATE feed SET created_at = DATE_SUB(NOW(), INTERVAL 48 HOUR) WHERE feed_id = ?")
                .setParameter(1, fOld.getFeedId()).executeUpdate();

        // 최신 저점수
        Feed fNew = saveFeed(clubB, u3, "new", "n.jpg");
        feedLikeRepository.save(FeedLike.builder().feed(fNew).user(u1).build());
        feedLikeRepository.save(FeedLike.builder().feed(fNew).user(u2).build());
        feedLikeRepository.save(FeedLike.builder().feed(fNew).user(u3).build());
        em.clear();

        String json = mvc.perform(get(FEEDS + "/popular"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Long> ids = data(json).findValues("feedId").stream().map(JsonNode::asLong).toList();
        assertThat(ids.indexOf(fNew.getFeedId())).isLessThan(ids.indexOf(fOld.getFeedId()));
    }

    @Test
    @DisplayName("리피드: 미가입자는 4xx")
    void refeed_nonMember_forbidden() throws Exception {
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");
        // u1은 clubC 미가입 → clubC로 리피드 시도시 4xx
        mvc.perform(post(FEEDS + "/{feedId}/{clubId}", parent.getFeedId(), clubC.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content","hello"))))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("리피드: 동일 사용자/클럽/원본으로 2회 시도시 4xx (중복)")
    void refeed_duplicate_throws() throws Exception {
        Feed parent = saveFeed(clubA, u2, "parent", "p.jpg");

        // 1회 OK
        mvc.perform(post(FEEDS + "/{feedId}/{clubId}", parent.getFeedId(), clubA.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content","first"))))
                .andExpect(status().isCreated());

        // 2회 → 4xx
        mvc.perform(post(FEEDS + "/{feedId}/{clubId}", parent.getFeedId(), clubA.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content","second"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("리피드: 자기 글 리피드 성공(201), 알림 발송 안 함")
    void refeed_ownPost_noNotification() throws Exception {
        Feed mine = saveFeed(clubA, u1, "mine", "m.jpg");

        mvc.perform(post(FEEDS + "/{feedId}/{clubId}", mine.getFeedId(), clubA.getClubId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content","self-quote"))))
                .andExpect(status().isCreated());

        Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("댓글 목록: ASC 정렬 & limit 반영")
    void commentList_sortedAndLimited() throws Exception {
        Feed feed = saveFeed(clubA, u2, "has comments", "img.jpg");
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u2).content("c1").build()); tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u1).content("c2").build()); tinySleep();
        feedCommentRepository.save(FeedComment.builder().feed(feed).user(u3).content("c3").build());
        em.flush(); em.clear();

        String jsonAsc = mvc.perform(get(FEEDS + "/{feedId}/comments", feed.getFeedId())
                        .param("page","0").param("limit","10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arrAsc = data(jsonAsc);
        assertThat(arrAsc.get(0).get("content").asText()).isEqualTo("c1");
        assertThat(arrAsc.get(1).get("content").asText()).isEqualTo("c2");
        assertThat(arrAsc.get(2).get("content").asText()).isEqualTo("c3");

        String jsonLimited = mvc.perform(get(FEEDS + "/{feedId}/comments", feed.getFeedId())
                        .param("page","0").param("limit","2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arrLim = data(jsonLimited);
        assertThat(arrLim.size()).isEqualTo(2);
        assertThat(arrLim.get(0).get("content").asText()).isEqualTo("c1");
        assertThat(arrLim.get(1).get("content").asText()).isEqualTo("c2");
    }
}
