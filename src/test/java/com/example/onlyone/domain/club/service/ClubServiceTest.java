package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
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
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ClubServiceTest {

    @Autowired ClubRepository clubRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserClubRepository userClubRepository;
    @Autowired InterestRepository interestRepository;
    @Autowired private ClubService clubService;
    @Autowired private ChatRoomRepository chatRoomRepository;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private NotificationService notificationService;


    private Interest exerciseInterest;
    private Interest cultureInterest;
    private User testUser1;
    private User testUser2;
    private User testUser3;
    private Club exerciseClubInSeoul;
    private Club cultureClubInSeoul;
    private Club exerciseClubInBusan;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        pageable = PageRequest.of(0, 20);

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

//    @AfterEach
//    void tearDown() {
//        userClubRepository.deleteAll();
//        chatRoomRepository.deleteAll();
//        clubRepository.deleteAll();
//        userRepository.deleteAll();
//        interestRepository.deleteAll();
//        }

    @DisplayName("해당 club의 clubRole이 LEADER인 유저가 모임 정보를 정상적으로 수정한다.")
    @Test
    void updateClub_success_byLeader() {
        //given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        ClubRequestDto request = createClubRequestDto(
                "서울 독서 모임(수정)", 30, "설명 수정", "updated.jpg",
                "부산", "해운대구", "CULTURE" // setUp에서 CULTURE Interest 이미 저장됨
        );

        // when
        clubService.updateClub(exerciseClubInSeoul.getClubId(), request);

        // then
        Club updated = clubRepository.findById(exerciseClubInSeoul.getClubId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("서울 독서 모임(수정)");
        assertThat(updated.getUserLimit()).isEqualTo(30);
        assertThat(updated.getDescription()).isEqualTo("설명 수정");
        assertThat(updated.getClubImage()).isEqualTo("updated.jpg");
        assertThat(updated.getCity()).isEqualTo("부산");
        assertThat(updated.getDistrict()).isEqualTo("해운대구");
        assertThat(updated.getInterest().getCategory()).isEqualTo(Category.CULTURE);
    }

    private ClubRequestDto createClubRequestDto(String name, int limit, String desc,
                               String img, String city, String district, String category) {
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

    @DisplayName("해당 club의 clubRole이 LEADER가 아닌 유저가 모임 정보를 수정할 수 없다.")
    @Test
    void updateClub_forbidden_whenNotLeader() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser2);

        // 요청 DTO (빌더 사용)
        ClubRequestDto req = ClubRequestDto.builder()
                .name("멤버가 수정 시도")
                .userLimit(25)
                .description("멤버는 수정 불가")
                .clubImage("try.jpg")
                .city("부산")
                .district("해운대구")
                .category("CULTURE")
                .build();

        // when & then
        assertThatThrownBy(() ->
                clubService.updateClub(exerciseClubInSeoul.getClubId(), req)
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);

        Club notChanged = clubRepository.findById(exerciseClubInSeoul.getClubId()).orElseThrow();
        assertThat(notChanged.getName()).isEqualTo("서울 축구 클럽");
        assertThat(notChanged.getUserLimit()).isEqualTo(20);
        assertThat(notChanged.getCity()).isEqualTo("서울");
        assertThat(notChanged.getDistrict()).isEqualTo("강남구");
    }

    @DisplayName("존재하지 않는 카테고리로 수정 시 예외가 발생한다")
    @Test
    void updateClub_interestNotFound() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        ClubRequestDto req = ClubRequestDto.builder()
                .name("이름수정")
                .userLimit(25)
                .description("설명수정")
                .clubImage("img.jpg")
                .city("서울")
                .district("강남구")
                .category("FIVE")
                .build();

        // when & then
        assertThatThrownBy(() ->
                clubService.updateClub(exerciseClubInSeoul.getClubId(), req)
        )
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CATEGORY);

        Club notChanged = clubRepository.findById(exerciseClubInSeoul.getClubId()).orElseThrow();
        assertThat(notChanged.getName()).isEqualTo("서울 축구 클럽");
        assertThat(notChanged.getInterest().getCategory()).isEqualTo(Category.EXERCISE);
    }

    @DisplayName("리더는 clubRole=LEADER로 조회된다")
    @Test
    void getClubDetail_returnsLeaderRole_forLeader() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        // when
        ClubDetailResponseDto dto = clubService.getClubDetail(exerciseClubInSeoul.getClubId());

        // then
        assertThat(dto.getClubRole()).isEqualTo(ClubRole.LEADER);
        assertThat(dto.getClubId()).isEqualTo(exerciseClubInSeoul.getClubId());
        assertThat(dto.getUserCount()).isEqualTo(2); // testUser1(리더) + testUser2(멤버)
    }

    @DisplayName("멤버는 clubRole=MEMBER로 조회된다")
    @Test
    void getClubDetail_returnsMemberRole_forMember() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser2);

        // when
        ClubDetailResponseDto dto = clubService.getClubDetail(exerciseClubInSeoul.getClubId());

        // then
        assertThat(dto.getClubRole()).isEqualTo(ClubRole.MEMBER);
        assertThat(dto.getClubId()).isEqualTo(exerciseClubInSeoul.getClubId());
        assertThat(dto.getUserCount()).isEqualTo(2);
    }

    @DisplayName("미가입자는 clubRole=GUEST로 조회된다")
    @Test
    void getClubDetail_returnsGuestRole_forNonMember() {
        // given: testUser3는 exerciseClubInSeoul에 가입되지 않음
        when(userService.getCurrentUser()).thenReturn(testUser3);

        // when
        ClubDetailResponseDto dto = clubService.getClubDetail(exerciseClubInSeoul.getClubId());

        // then
        assertThat(dto.getClubRole()).isEqualTo(ClubRole.GUEST);
        assertThat(dto.getClubId()).isEqualTo(exerciseClubInSeoul.getClubId());
        assertThat(dto.getUserCount()).isEqualTo(2); // 리더(testUser1) + 멤버(testUser2)
    }

    @DisplayName("userCount는 실제 회원 수와 일치한다")
    @Test
    void getClubDetail_userCount_matches_memberCount() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);

        // 현재 회원 수는 2명(리더 testUser1 + 멤버 testUser2)
        int userCount = userClubRepository.countByClub_ClubId(exerciseClubInSeoul.getClubId());
        assertThat(userCount).isEqualTo(2);

        ClubDetailResponseDto dto = clubService.getClubDetail(exerciseClubInSeoul.getClubId());
        assertThat(dto.getUserCount()).isEqualTo(userCount);

        // when: 새 멤버를 추가로 가입시킴
        User extra = userRepository.save(User.builder()
                .kakaoId(99999L)
                .nickname("추가멤버")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1992, 2, 2))
                .city("서울")
                .district("강남구")
                .build());

        userClubRepository.save(UserClub.builder()
                .user(extra)
                .club(exerciseClubInSeoul)
                .clubRole(ClubRole.MEMBER)
                .build());

        // then
        int userCountAfter = userClubRepository.countByClub_ClubId(exerciseClubInSeoul.getClubId());
        assertThat(userCountAfter).isEqualTo(3);

        ClubDetailResponseDto dtoAfter = clubService.getClubDetail(exerciseClubInSeoul.getClubId());
        assertThat(dtoAfter.getUserCount()).isEqualTo(userCountAfter).isEqualTo(3);
    }

    @DisplayName("존재하지 않는 모임 조회 시 실패한다.")
    @Test
    void getClubDetail_throws_whenClubNotFound() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);
        Long nonexistentId = Long.MAX_VALUE;

        // when & then
        assertThatThrownBy(() -> clubService.getClubDetail(nonexistentId))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_NOT_FOUND);
    }

    @DisplayName("이미 가입한 사용자가 재가입 시도하면 예외가 발생한다.")
    @Test
    void joinClub_throws_whenAlreadyJoined() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);
        int before = userClubRepository.countByClub_ClubId(exerciseClubInSeoul.getClubId());

        // when & then
        assertThatThrownBy(() -> clubService.joinClub(exerciseClubInSeoul.getClubId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

        // 부수효과 없음 확인
        int after = userClubRepository.countByClub_ClubId(exerciseClubInSeoul.getClubId());
        assertThat(after).isEqualTo(before);
    }

    @DisplayName("정원 초과 시 가입 불가")
    @Test
    void joinClub_throws_whenCapacityReached_busan() {
        // 미가입자
        when(userService.getCurrentUser()).thenReturn(testUser1);

        // when & then
        assertThatThrownBy(() -> clubService.joinClub(exerciseClubInBusan.getClubId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_NOT_ENTER);
    }

    @DisplayName("리더는 모임을 탈퇴할 수 없다.")
    @Test
    void leaveClub_throws_whenLeaderTriesToLeave() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser1);
        int before = userClubRepository.countByClub_ClubId(exerciseClubInSeoul.getClubId());
        assertThat(userClubRepository.existsByUser_UserIdAndClub_ClubId(
                testUser1.getUserId(), exerciseClubInSeoul.getClubId())).isTrue();

        // when & then
        assertThatThrownBy(() -> clubService.leaveClub(exerciseClubInSeoul.getClubId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CLUB_LEADER_NOT_LEAVE);

        // 못 나갔는까지 체크
        int after = userClubRepository.countByClub_ClubId(exerciseClubInSeoul.getClubId());
        assertThat(after).isEqualTo(before);
        assertThat(userClubRepository.existsByUser_UserIdAndClub_ClubId(
                testUser1.getUserId(), exerciseClubInSeoul.getClubId())).isTrue();
    }

    @DisplayName("모임에 가입하지 않은 자가 모임을 탈퇴하려고 할 경우 예러가 발생한다. ")
    @Test
    void leaveClub_throws_whenNonMember() {
        // given
        when(userService.getCurrentUser()).thenReturn(testUser3);
        Long clubId = exerciseClubInSeoul.getClubId();

        // 미가입자 확인
        assertThat(userClubRepository.existsByUser_UserIdAndClub_ClubId(
                testUser3.getUserId(), clubId)).isFalse();
        int before = userClubRepository.countByClub_ClubId(clubId);

        // when & then
        assertThatThrownBy(() -> clubService.leaveClub(clubId))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_CLUB_NOT_FOUND);

        // 못 나갔는지 체크
        int after = userClubRepository.countByClub_ClubId(clubId);
        assertThat(after).isEqualTo(before);
        assertThat(userClubRepository.existsByUser_UserIdAndClub_ClubId(
                testUser3.getUserId(), clubId)).isFalse();
    }

    @DisplayName("탈퇴 후 재가입이 정상 동작한다.")
    @Test
    void leave_then_rejoin_success() {
        //given
        when(userService.getCurrentUser()).thenReturn(testUser2);
        Long clubId = exerciseClubInSeoul.getClubId();

        chatRoomRepository.save(ChatRoom.builder()
                        .club(exerciseClubInSeoul)
                        .type(Type.CLUB)
                        .build());

        assertThat(userClubRepository.countByClub_ClubId(clubId)).isEqualTo(2);
        assertThat(userClubRepository.findByUserAndClub(testUser2, exerciseClubInSeoul)).isPresent();

        clubService.leaveClub(clubId);

        assertThat(userClubRepository.countByClub_ClubId(clubId)).isEqualTo(1);
        assertThat(userClubRepository.findByUserAndClub(testUser2, exerciseClubInSeoul)).isNotPresent();
        //when
        clubService.joinClub(clubId);
        //then
        assertThat(userClubRepository.countByClub_ClubId(clubId)).isEqualTo(2);
        UserClub rejoined = userClubRepository.findByUserAndClub(testUser2, exerciseClubInSeoul).orElseThrow();
        assertThat(rejoined.getClubRole()).isEqualTo(ClubRole.MEMBER);

    }

}