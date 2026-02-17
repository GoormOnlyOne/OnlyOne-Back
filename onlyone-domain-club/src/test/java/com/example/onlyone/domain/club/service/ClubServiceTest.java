package com.example.onlyone.domain.club.service;

import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClubService 단위 테스트")
class ClubServiceTest {

    @InjectMocks
    private ClubService clubService;

    @Mock
    private ClubRepository clubRepository;

    @Mock
    private InterestRepository interestRepository;

    @Mock
    private UserClubRepository userClubRepository;

    @Mock
    private UserService userService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Nested
    @DisplayName("모임 생성")
    class CreateClub {

        @Test
        @DisplayName("성공: 리더 역할로 모임이 생성되고 이벤트가 발행된다")
        void createClub_success() {
            // given
            Interest interest = createInterest();
            ClubRequestDto requestDto = createClubRequestDto();
            User user = createUser(1L);

            // save 시 JPA의 ID 자동생성을 시뮬레이션
            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.of(interest));
            given(clubRepository.save(any(Club.class))).willAnswer(invocation -> {
                Club c = invocation.getArgument(0);
                ReflectionTestUtils.setField(c, "clubId", 1L);
                return c;
            });
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.save(any(UserClub.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(clubRepository.incrementMemberCount(1L)).willReturn(1);

            // when
            ClubCreateResponseDto result = clubService.createClub(requestDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.clubId()).isEqualTo(1L);

            // UserClub이 LEADER 역할로 저장되었는지 검증
            ArgumentCaptor<UserClub> userClubCaptor = ArgumentCaptor.forClass(UserClub.class);
            verify(userClubRepository).save(userClubCaptor.capture());
            assertThat(userClubCaptor.getValue().getClubRole()).isEqualTo(ClubRole.LEADER);
            assertThat(userClubCaptor.getValue().getUser()).isEqualTo(user);

            // ClubCreatedEvent 발행 검증
            ArgumentCaptor<ClubCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ClubCreatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().clubId()).isEqualTo(1L);
            assertThat(eventCaptor.getValue().leaderUserId()).isEqualTo(user.getUserId());

            verify(clubRepository).incrementMemberCount(1L);
        }

        @Test
        @DisplayName("실패: 존재하지 않는 관심사면 INTEREST_NOT_FOUND")
        void createClub_fail_interestNotFound() {
            // given
            ClubRequestDto requestDto = createClubRequestDto();

            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> clubService.createClub(requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INTEREST_NOT_FOUND);

            verify(clubRepository, never()).save(any(Club.class));
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("모임 수정")
    class UpdateClub {

        @Test
        @DisplayName("성공: 리더가 모임 정보를 수정한다")
        void updateClub_success() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(1L);
            UserClub userClub = UserClub.builder()
                    .userClubId(1L)
                    .user(user)
                    .club(club)
                    .clubRole(ClubRole.LEADER)
                    .build();

            Interest newInterest = Interest.builder()
                    .interestId(2L)
                    .category(Category.CULTURE)
                    .build();

            ClubRequestDto requestDto = new ClubRequestDto(
                    "수정된 모임", 30, "수정된 설명", "updated.jpg", "부산", "해운대구", "EXERCISE");

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.of(newInterest));
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when
            ClubCreateResponseDto result = clubService.updateClub(1L, requestDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.clubId()).isEqualTo(1L);
            assertThat(club.getName()).isEqualTo("수정된 모임");
            assertThat(club.getUserLimit()).isEqualTo(30);
            assertThat(club.getDescription()).isEqualTo("수정된 설명");
            assertThat(club.getClubImage()).isEqualTo("updated.jpg");
            assertThat(club.getCity()).isEqualTo("부산");
            assertThat(club.getDistrict()).isEqualTo("해운대구");
        }

        @Test
        @DisplayName("실패: 모임이 존재하지 않으면 CLUB_NOT_FOUND")
        void updateClub_fail_clubNotFound() {
            // given
            ClubRequestDto requestDto = createClubRequestDto();

            given(clubRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> clubService.updateClub(999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 MEMBER_CANNOT_MODIFY_SCHEDULE")
        void updateClub_fail_notLeader() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(1L);
            UserClub userClub = UserClub.builder()
                    .userClubId(1L)
                    .user(user)
                    .club(club)
                    .clubRole(ClubRole.MEMBER)
                    .build();

            ClubRequestDto requestDto = createClubRequestDto();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.of(interest));
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when & then
            assertThatThrownBy(() -> clubService.updateClub(1L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }
    }

    @Nested
    @DisplayName("모임 가입")
    class JoinClub {

        @Test
        @DisplayName("성공: 멤버 역할로 모임에 가입한다")
        void joinClub_success() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(2L);

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.empty());
            given(userClubRepository.save(any(UserClub.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(clubRepository.incrementMemberCount(1L)).willReturn(1);

            // when
            clubService.joinClub(1L);

            // then
            ArgumentCaptor<UserClub> captor = ArgumentCaptor.forClass(UserClub.class);
            verify(userClubRepository).save(captor.capture());
            assertThat(captor.getValue().getClubRole()).isEqualTo(ClubRole.MEMBER);
            assertThat(captor.getValue().getUser()).isEqualTo(user);
            assertThat(captor.getValue().getClub()).isEqualTo(club);

            verify(clubRepository).incrementMemberCount(1L);
        }

        @Test
        @DisplayName("실패: 정원 초과시 CLUB_NOT_ENTER")
        void joinClub_fail_capacityExceeded() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest); // userLimit = 10

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(10); // 정원 가득 참

            // when & then
            assertThatThrownBy(() -> clubService.joinClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_ENTER);

            verify(userClubRepository, never()).save(any(UserClub.class));
            verify(clubRepository, never()).incrementMemberCount(anyLong());
        }

        @Test
        @DisplayName("실패: 이미 가입한 경우 ALREADY_JOINED_CLUB")
        void joinClub_fail_alreadyJoined() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(1L);
            UserClub existingUserClub = UserClub.builder()
                    .userClubId(1L)
                    .user(user)
                    .club(club)
                    .clubRole(ClubRole.MEMBER)
                    .build();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(existingUserClub));

            // when & then
            assertThatThrownBy(() -> clubService.joinClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

            verify(userClubRepository, never()).save(any(UserClub.class));
            verify(clubRepository, never()).incrementMemberCount(anyLong());
        }
    }

    @Nested
    @DisplayName("모임 탈퇴")
    class LeaveClub {

        @Test
        @DisplayName("성공: 멤버가 모임을 탈퇴한다")
        void leaveClub_success() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(2L);
            UserClub userClub = UserClub.builder()
                    .userClubId(1L)
                    .user(user)
                    .club(club)
                    .clubRole(ClubRole.MEMBER)
                    .build();

            given(userService.getCurrentUser()).willReturn(user);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));
            given(clubRepository.decrementMemberCount(1L)).willReturn(1);

            // when
            clubService.leaveClub(1L);

            // then
            verify(userClubRepository).delete(userClub);
            verify(clubRepository).decrementMemberCount(1L);
        }

        @Test
        @DisplayName("실패: GUEST는 탈퇴 불가 CLUB_NOT_LEAVE")
        void leaveClub_fail_guestCannotLeave() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(3L);
            UserClub userClub = UserClub.builder()
                    .userClubId(1L)
                    .user(user)
                    .club(club)
                    .clubRole(ClubRole.GUEST)
                    .build();

            given(userService.getCurrentUser()).willReturn(user);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when & then
            assertThatThrownBy(() -> clubService.leaveClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_LEAVE);

            verify(userClubRepository, never()).delete(any(UserClub.class));
            verify(clubRepository, never()).decrementMemberCount(anyLong());
        }

        @Test
        @DisplayName("실패: 리더는 탈퇴 불가 CLUB_LEADER_NOT_LEAVE")
        void leaveClub_fail_leaderCannotLeave() {
            // given
            Interest interest = createInterest();
            Club club = createClub(1L, interest);
            User user = createUser(1L);
            UserClub userClub = UserClub.builder()
                    .userClubId(1L)
                    .user(user)
                    .club(club)
                    .clubRole(ClubRole.LEADER)
                    .build();

            given(userService.getCurrentUser()).willReturn(user);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when & then
            assertThatThrownBy(() -> clubService.leaveClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_LEADER_NOT_LEAVE);

            verify(userClubRepository, never()).delete(any(UserClub.class));
            verify(clubRepository, never()).decrementMemberCount(anyLong());
        }
    }

    // ========== Helper Methods ==========

    private User createUser(Long id) {
        return User.builder()
                .userId(id)
                .kakaoId(10000L + id)
                .nickname("테스트유저" + id)
                .birth(LocalDate.of(1990, 1, 1))
                .status(Status.ACTIVE)
                .profileImage("profile.jpg")
                .gender(Gender.MALE)
                .city("서울")
                .district("강남구")
                .build();
    }

    private Club createClub(Long id, Interest interest) {
        return Club.builder()
                .clubId(id)
                .name("테스트 모임")
                .userLimit(10)
                .description("테스트 모임 설명")
                .clubImage("club.jpg")
                .city("서울")
                .district("강남구")
                .interest(interest)
                .build();
    }

    private Interest createInterest() {
        return Interest.builder()
                .interestId(1L)
                .category(Category.EXERCISE)
                .build();
    }

    private ClubRequestDto createClubRequestDto() {
        return new ClubRequestDto(
                "테스트 모임", 10, "테스트 모임 설명", "club.jpg", "서울", "강남구", "EXERCISE");
    }
}
