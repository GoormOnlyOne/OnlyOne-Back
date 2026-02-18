package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static com.example.onlyone.test.ClubFixtures.aClub;
import static com.example.onlyone.test.UserFixtures.aUser;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("모임 가입 중복 방지 단위 테스트")
class ClubJoinServiceTest {

    @InjectMocks
    private ClubCommandService clubCommandService;

    @Mock private ClubRepository clubRepository;
    @Mock private InterestRepository interestRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("애플리케이션 레벨 중복 검사: existsByUser_UserIdAndClub_ClubId로 이미 가입된 경우 차단")
    void joinClub_applicationLevel_duplicatePrevented() {
        // given
        Club club = aClub().build();
        User user = aUser(1L).build();

        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
        given(userService.getCurrentUser()).willReturn(user);
        given(userClubRepository.existsByUser_UserIdAndClub_ClubId(1L, 1L)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> clubCommandService.joinClub(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

        then(userClubRepository).should(never()).save(any(UserClub.class));
        then(clubRepository).should(never()).incrementMemberCount(anyLong());
    }

    @Test
    @DisplayName("DB 레벨 중복 방지: UniqueConstraint 위반 시 DataIntegrityViolationException 처리")
    void joinClub_dbLevel_uniqueConstraintViolation() {
        // given
        Club club = aClub().build();
        User user = aUser(1L).build();

        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
        given(userService.getCurrentUser()).willReturn(user);
        given(userClubRepository.existsByUser_UserIdAndClub_ClubId(1L, 1L)).willReturn(false);
        given(userClubRepository.save(any(UserClub.class))).willReturn(
                UserClub.builder().user(user).club(club).clubRole(ClubRole.MEMBER).build());
        willThrow(new DataIntegrityViolationException("Duplicate entry"))
                .given(userClubRepository).flush();

        // when & then
        assertThatThrownBy(() -> clubCommandService.joinClub(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

        then(clubRepository).should(never()).incrementMemberCount(anyLong());
    }

    @Test
    @DisplayName("정상 가입: 중복이 아닌 경우 정상적으로 가입된다")
    void joinClub_success_noDuplicate() {
        // given
        Club club = aClub().build();
        User user = aUser(1L).build();

        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
        given(userService.getCurrentUser()).willReturn(user);
        given(userClubRepository.existsByUser_UserIdAndClub_ClubId(1L, 1L)).willReturn(false);
        given(userClubRepository.save(any(UserClub.class))).willAnswer(inv -> inv.getArgument(0));
        given(clubRepository.incrementMemberCount(1L)).willReturn(1);

        // when
        clubCommandService.joinClub(1L);

        // then
        then(userClubRepository).should().save(any(UserClub.class));
        then(clubRepository).should().incrementMemberCount(1L);
    }

    @Test
    @DisplayName("정원 초과: 정원이 가득 찬 경우 가입 불가")
    void joinClub_capacityExceeded() {
        // given
        Club club = aClub().build();

        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(10);

        // when & then
        assertThatThrownBy(() -> clubCommandService.joinClub(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_ENTER);

        then(userClubRepository).should(never()).save(any(UserClub.class));
    }
}
