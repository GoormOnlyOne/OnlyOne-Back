package com.example.onlyone.domain.club.controller;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터는 별도 테스트에서
@Transactional
class ClubControllerIT {

    private static final String BASE = "/clubs";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Autowired ClubRepository clubRepository;
    @Autowired UserClubRepository userClubRepository;
    @Autowired UserRepository userRepository;
    @Autowired InterestRepository interestRepository;

    @MockitoBean
    UserService userService;
    @MockitoBean NotificationService notificationService;

    Interest exerciseInterest;
    Interest cultureInterest;
    User testUser1; // 기본: 리더
    User testUser2; // 멤버/비회원 시나리오
    User testUser3; // 비회원 시나리오

    @BeforeEach
    void setUp() {
        exerciseInterest = interestRepository.save(Interest.builder().category(Category.EXERCISE).build());
        cultureInterest  = interestRepository.save(Interest.builder().category(Category.CULTURE).build());

        testUser1 = userRepository.save(User.builder()
                .kakaoId(12345L).nickname("테스트유저1")
                .status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1990,1,1)).city("서울").district("강남구")
                .build());

        testUser2 = userRepository.save(User.builder()
                .kakaoId(12346L).nickname("테스트유저2")
                .status(Status.ACTIVE).gender(Gender.FEMALE)
                .birth(LocalDate.of(1995,5,15)).city("서울").district("강남구")
                .build());

        testUser3 = userRepository.save(User.builder()
                .kakaoId(12347L).nickname("테스트유저3")
                .status(Status.ACTIVE).gender(Gender.MALE)
                .birth(LocalDate.of(1985,12,20)).city("부산").district("해운대구")
                .build());

        // 기본 로그인 유저는 testUser1
        given(userService.getCurrentUser()).willReturn(testUser1);
    }

    // 바디 빌더: Map 대신 실제 DTO 사용 (키 오타 방지)
    private ClubRequestDto newClubRequest(String name, int limit, String desc, String img,
                                          String city, String district, String category) {
        return ClubRequestDto.builder()
                .name(name)
                .userLimit(limit)
                .description(desc)
                .clubImage(img)
                .city(city)
                .district(district)
                .category(category)
                .build();
    }

    // CommonResponse.success({...})에서 data 노드 안전히 추출
    private JsonNode getDataNode(String content) throws Exception {
        JsonNode root = om.readTree(content);
        return root.has("data") ? root.get("data") : root;
    }

    private long createClubAndGetId(String name, int limit, String city, String district, String category) throws Exception {
        ClubRequestDto dto = newClubRequest(name, limit, name + " 설명", "img.png", city, district, category);

        String content = mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.clubId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return getDataNode(content).get("clubId").asLong();
    }

    // ===== 테스트 =====

    @Test
    @DisplayName("클럽 생성: 201, data.clubId 반환, 생성자는 LEADER, 상세조회 userCount=1")
    void create_returns201_andLeader() throws Exception {
        long clubId = createClubAndGetId("서울 축구 클럽", 20, "서울", "강남구", "EXERCISE");

        Club saved = clubRepository.findById(clubId).orElseThrow();
        assertThat(saved.getName()).isEqualTo("서울 축구 클럽");
        assertThat(saved.getInterest().getCategory()).isEqualTo(Category.EXERCISE);

        UserClub uc = userClubRepository.findByUserAndClub(testUser1, saved).orElseThrow();
        assertThat(uc.getClubRole()).isEqualTo(ClubRole.LEADER);

        mvc.perform(get(BASE + "/{id}", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubId").value(clubId))
                .andExpect(jsonPath("$.data.name").value("서울 축구 클럽"))
                .andExpect(jsonPath("$.data.category").value("EXERCISE"))
                .andExpect(jsonPath("$.data.userLimit").value(20))
                .andExpect(jsonPath("$.data.city").value("서울"))
                .andExpect(jsonPath("$.data.district").value("강남구"))
                .andExpect(jsonPath("$.data.clubRole").value("LEADER"))
                .andExpect(jsonPath("$.data.userCount").value(1));
    }

    @Test
    @DisplayName("검증 실패: 모임명이 21자면 400")
    void create_validation_400_whenNameTooLong() throws Exception {
        String over20 = "a".repeat(21);
        var badReq = newClubRequest(over20, 10, "desc", "img.png", "서울", "강남구", "EXERCISE");

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(badReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("상세 조회 clubRole 분기: 리더→멤버→게스트")
    void detail_roleVariants() throws Exception {
        long clubId = createClubAndGetId("서울 독서 모임", 15, "서울", "강남구", "CULTURE");

        // 리더
        given(userService.getCurrentUser()).willReturn(testUser1);
        mvc.perform(get(BASE + "/{id}", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubRole").value("LEADER"))
                .andExpect(jsonPath("$.data.userCount").value(1));

        // 멤버(가입 후)
        given(userService.getCurrentUser()).willReturn(testUser2);
        mvc.perform(post(BASE + "/{id}/join", clubId))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "/{id}", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubRole").value("MEMBER"))
                .andExpect(jsonPath("$.data.userCount").value(2));

        // 게스트(미가입)
        given(userService.getCurrentUser()).willReturn(testUser3);
        mvc.perform(get(BASE + "/{id}", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubRole").value("GUEST"))
                .andExpect(jsonPath("$.data.userCount").value(2));
    }

    @Test
    @DisplayName("리더만 수정 가능: 리더 PATCH 200, 멤버/비회원은 4xx")
    void update_leaderOnly() throws Exception {
        long clubId = createClubAndGetId("서울 운동 모임", 20, "서울", "강남구", "EXERCISE");

        // 리더 수정 OK
        given(userService.getCurrentUser()).willReturn(testUser1);
        var patchReq = newClubRequest("이름수정", 30, "설명 수정", "updated.png", "부산", "해운대구", "CULTURE");

        mvc.perform(patch(BASE + "/{id}", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        mvc.perform(get(BASE + "/{id}", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("이름수정"))
                .andExpect(jsonPath("$.data.userLimit").value(30))
                .andExpect(jsonPath("$.data.district").value("해운대구"))
                .andExpect(jsonPath("$.data.category").value("CULTURE"));

        // 멤버는 금지
        given(userService.getCurrentUser()).willReturn(testUser2);
        mvc.perform(post(BASE + "/{id}/join", clubId)).andExpect(status().isOk());

        mvc.perform(patch(BASE + "/{id}", clubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(newClubRequest("멤버수정", 10, "d","i","서울","강남구","EXERCISE"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("가입 엣지 케이스: 이미 가입자, 정원 초과는 4xx")
    void join_edgeCases() throws Exception {
        // 정원 1: 생성과 동시에 리더 1명으로 꽉 참
        long fullClubId = createClubAndGetId("부산 테니스 클럽", 1, "부산", "해운대구", "EXERCISE");

        // 이미 가입(리더) 사용자 재가입 시도 → 4xx
        given(userService.getCurrentUser()).willReturn(testUser1);
        mvc.perform(post(BASE + "/{id}/join", fullClubId))
                .andExpect(status().is4xxClientError());

        // 정원 초과: 다른 사용자 가입 시도 → 4xx
        given(userService.getCurrentUser()).willReturn(testUser2);
        mvc.perform(post(BASE + "/{id}/join", fullClubId))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("탈퇴: 멤버는 OK 후 GUEST로 보이고 인원 수 감소")
    void leave_member_ok() throws Exception {
        long clubId = createClubAndGetId("서울 독서 모임", 15, "서울", "강남구", "CULTURE");

        // 멤버 가입
        given(userService.getCurrentUser()).willReturn(testUser2);
        mvc.perform(post(BASE + "/{id}/join", clubId)).andExpect(status().isOk());
        assertThat(userClubRepository.countByClub_ClubId(clubId)).isEqualTo(2);

        // 탈퇴
        mvc.perform(delete(BASE + "/{id}/leave", clubId))
                .andExpect(status().isOk());
        assertThat(userClubRepository.countByClub_ClubId(clubId)).isEqualTo(1);

        // 상세: 다시 게스트
        mvc.perform(get(BASE + "/{id}", clubId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubRole").value("GUEST"))
                .andExpect(jsonPath("$.data.userCount").value(1));
    }

    @Test
    @DisplayName("리더는 탈퇴 불가(4xx), 비회원도 탈퇴 불가(4xx)")
    void leave_forbidden_cases() throws Exception {
        long clubId = createClubAndGetId("서울 축구 클럽", 20, "서울", "강남구", "EXERCISE");

        // 리더 탈퇴 불가
        given(userService.getCurrentUser()).willReturn(testUser1);
        mvc.perform(delete(BASE + "/{id}/leave", clubId))
                .andExpect(status().is4xxClientError());

        // 비회원 탈퇴 불가
        given(userService.getCurrentUser()).willReturn(testUser3);
        mvc.perform(delete(BASE + "/{id}/leave", clubId))
                .andExpect(status().is4xxClientError());
    }
}
