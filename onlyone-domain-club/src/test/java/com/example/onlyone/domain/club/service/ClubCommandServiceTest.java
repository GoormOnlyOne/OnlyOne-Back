package com.example.onlyone.domain.club.service;

import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.common.event.ClubLeftEvent;
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

import java.util.Optional;

import static com.example.onlyone.test.ClubFixtures.*;
import static com.example.onlyone.test.UserFixtures.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClubCommandService 단위 테스트")
class ClubCommandServiceTest {

    @InjectMocks
    private ClubCommandService clubCommandService;

    @Mock private ClubRepository clubRepository;
    @Mock private InterestRepository interestRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Nested
    @DisplayName("모임 생성")
    class CreateClub {

        @Test
        @DisplayName("성공: 리더 역할로 모임이 생성되고 이벤트가 발행된다")
        void createClub_success() {
            // given
            Interest interest = anInterest().build();
            ClubRequestDto requestDto = aClubRequestDto();
            User user = aUser(1L).build();

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
            ClubCreateResponseDto result = clubCommandService.createClub(requestDto);

            // then
            assertThat(result).isNotNull();
            assertThat(result.clubId()).isEqualTo(1L);

            ArgumentCaptor<UserClub> userClubCaptor = ArgumentCaptor.forClass(UserClub.class);
            then(userClubRepository).should().save(userClubCaptor.capture());
            assertThat(userClubCaptor.getValue().getClubRole()).isEqualTo(ClubRole.LEADER);
            assertThat(userClubCaptor.getValue().getUser()).isEqualTo(user);

            ArgumentCaptor<ClubCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ClubCreatedEvent.class);
            then(eventPublisher).should().publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().clubId()).isEqualTo(1L);
            assertThat(eventCaptor.getValue().leaderUserId()).isEqualTo(user.getUserId());

            then(clubRepository).should().incrementMemberCount(1L);
        }

        @Test
        @DisplayName("실패: 존재하지 않는 관심사면 INTEREST_NOT_FOUND")
        void createClub_fail_interestNotFound() {
            // given
            ClubRequestDto requestDto = aClubRequestDto();

            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> clubCommandService.createClub(requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INTEREST_NOT_FOUND);

            then(clubRepository).should(never()).save(any(Club.class));
            then(eventPublisher).should(never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("모임 수정")
    class UpdateClub {

        @Test
        @DisplayName("성공: 리더가 모임 정보를 수정한다")
        void updateClub_success() {
            // given
            Club club = aClub().build();
            User user = aUser(1L).build();
            UserClub userClub = aUserClub(user, club, ClubRole.LEADER).userClubId(1L).build();

            Interest newInterest = Interest.builder()
                    .interestId(2L).category(Category.CULTURE).build();

            ClubRequestDto requestDto = new ClubRequestDto(
                    "수정된 모임", 30, "수정된 설명", "updated.jpg", "부산", "해운대구", "EXERCISE");

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.of(newInterest));
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when
            ClubCreateResponseDto result = clubCommandService.updateClub(1L, requestDto);

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
            ClubRequestDto requestDto = aClubRequestDto();
            given(clubRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> clubCommandService.updateClub(999L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 리더가 아니면 LEADER_ONLY_CLUB_MODIFY")
        void updateClub_fail_notLeader() {
            // given
            Interest interest = anInterest().build();
            Club club = aClub().build();
            User user = aUser(1L).build();
            UserClub userClub = aUserClub(user, club, ClubRole.MEMBER).userClubId(1L).build();

            ClubRequestDto requestDto = aClubRequestDto();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(interestRepository.findByCategory(Category.EXERCISE))
                    .willReturn(Optional.of(interest));
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when & then
            assertThatThrownBy(() -> clubCommandService.updateClub(1L, requestDto))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LEADER_ONLY_CLUB_MODIFY);
        }
    }

    @Nested
    @DisplayName("모임 가입")
    class JoinClub {

        @Test
        @DisplayName("성공: 멤버 역할로 모임에 가입한다")
        void joinClub_success() {
            // given
            Club club = aClub().build();
            User user = aUser(2L).build();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(2L, 1L))
                    .willReturn(false);
            given(userClubRepository.save(any(UserClub.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(clubRepository.incrementMemberCount(1L)).willReturn(1);

            // when
            clubCommandService.joinClub(1L);

            // then
            ArgumentCaptor<UserClub> captor = ArgumentCaptor.forClass(UserClub.class);
            then(userClubRepository).should().save(captor.capture());
            assertThat(captor.getValue().getClubRole()).isEqualTo(ClubRole.MEMBER);
            assertThat(captor.getValue().getUser()).isEqualTo(user);
            assertThat(captor.getValue().getClub()).isEqualTo(club);

            then(clubRepository).should().incrementMemberCount(1L);
        }

        @Test
        @DisplayName("실패: 정원 초과시 CLUB_NOT_ENTER")
        void joinClub_fail_capacityExceeded() {
            // given
            Club club = aClub().build();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(10);

            // when & then
            assertThatThrownBy(() -> clubCommandService.joinClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_ENTER);

            then(userClubRepository).should(never()).save(any(UserClub.class));
            then(clubRepository).should(never()).incrementMemberCount(anyLong());
        }

        @Test
        @DisplayName("실패: 이미 가입한 경우 ALREADY_JOINED_CLUB")
        void joinClub_fail_alreadyJoined() {
            // given
            Club club = aClub().build();
            User user = aUser(1L).build();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(1L, 1L))
                    .willReturn(true);

            // when & then
            assertThatThrownBy(() -> clubCommandService.joinClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

            then(userClubRepository).should(never()).save(any(UserClub.class));
            then(clubRepository).should(never()).incrementMemberCount(anyLong());
        }
    }

    @Nested
    @DisplayName("모임 탈퇴")
    class LeaveClub {

        @Test
        @DisplayName("성공: 멤버가 모임을 탈퇴한다")
        void leaveClub_success() {
            // given
            Club club = aClub().build();
            User user = aUser(2L).build();
            UserClub userClub = aUserClub(user, club, ClubRole.MEMBER).userClubId(1L).build();

            given(userService.getCurrentUser()).willReturn(user);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));
            given(clubRepository.decrementMemberCount(1L)).willReturn(1);

            // when
            clubCommandService.leaveClub(1L);

            // then
            then(userClubRepository).should().delete(userClub);
            then(clubRepository).should().decrementMemberCount(1L);

            ArgumentCaptor<ClubLeftEvent> eventCaptor = ArgumentCaptor.forClass(ClubLeftEvent.class);
            then(eventPublisher).should().publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().clubId()).isEqualTo(1L);
            assertThat(eventCaptor.getValue().userId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("실패: GUEST는 탈퇴 불가 CLUB_NOT_LEAVE")
        void leaveClub_fail_guestCannotLeave() {
            // given
            Club club = aClub().build();
            User user = aUser(3L).build();
            UserClub userClub = aUserClub(user, club, ClubRole.GUEST).userClubId(1L).build();

            given(userService.getCurrentUser()).willReturn(user);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when & then
            assertThatThrownBy(() -> clubCommandService.leaveClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_LEAVE);

            then(userClubRepository).should(never()).delete(any(UserClub.class));
            then(clubRepository).should(never()).decrementMemberCount(anyLong());
        }

        @Test
        @DisplayName("실패: 리더는 탈퇴 불가 CLUB_LEADER_NOT_LEAVE")
        void leaveClub_fail_leaderCannotLeave() {
            // given
            Club club = aClub().build();
            User user = aUser(1L).build();
            UserClub userClub = aUserClub(user, club, ClubRole.LEADER).userClubId(1L).build();

            given(userService.getCurrentUser()).willReturn(user);
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userClubRepository.findByUserAndClub(user, club))
                    .willReturn(Optional.of(userClub));

            // when & then
            assertThatThrownBy(() -> clubCommandService.leaveClub(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_LEADER_NOT_LEAVE);

            then(userClubRepository).should(never()).delete(any(UserClub.class));
            then(clubRepository).should(never()).decrementMemberCount(anyLong());
        }
    }
}
