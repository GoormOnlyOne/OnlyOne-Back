package com.example.onlyone.domain.club.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("모임 가입 중복 방지 통합 테스트")
class ClubJoinIntegrationTest {

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

    private User user;
    private Club club;
    private Interest interest;

    @BeforeEach
    void setUp() {
        interest = Interest.builder()
                .interestId(1L)
                .category(Category.EXERCISE)
                .build();

        user = User.builder()
                .userId(1L)
                .kakaoId(1000L)
                .nickname("테스트유저")
                .birth(LocalDate.of(1995, 1, 1))
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .city("서울")
                .district("강남구")
                .build();

        club = Club.builder()
                .clubId(1L)
                .name("테스트 모임")
                .userLimit(10)
                .description("테스트 설명")
                .clubImage("club.jpg")
                .city("서울")
                .district("강남구")
                .interest(interest)
                .build();
    }

    @Test
    @DisplayName("애플리케이션 레벨 중복 검사: findByUserAndClub으로 이미 가입된 경우 차단")
    void joinClub_applicationLevel_duplicatePrevented() {
        // given
        UserClub existing = UserClub.builder()
                .userClubId(1L)
                .user(user)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();

        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
        given(userService.getCurrentUser()).willReturn(user);
        given(userClubRepository.findByUserAndClub(user, club)).willReturn(Optional.of(existing));

        // when & then
        assertThatThrownBy(() -> clubService.joinClub(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

        verify(userClubRepository, never()).save(any(UserClub.class));
        verify(clubRepository, never()).incrementMemberCount(anyLong());
    }

    @Test
    @DisplayName("DB 레벨 중복 방지: UniqueConstraint 위반 시 DataIntegrityViolationException 처리")
    void joinClub_dbLevel_uniqueConstraintViolation() {
        // given
        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
        given(userService.getCurrentUser()).willReturn(user);
        given(userClubRepository.findByUserAndClub(user, club)).willReturn(Optional.empty());
        given(userClubRepository.save(any(UserClub.class))).willReturn(
                UserClub.builder().user(user).club(club).clubRole(ClubRole.MEMBER).build());
        doThrow(new DataIntegrityViolationException("Duplicate entry"))
                .when(userClubRepository).flush();

        // when & then
        assertThatThrownBy(() -> clubService.joinClub(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_JOINED_CLUB);

        verify(clubRepository, never()).incrementMemberCount(anyLong());
    }

    @Test
    @DisplayName("정상 가입: 중복이 아닌 경우 정상적으로 가입된다")
    void joinClub_success_noDuplicate() {
        // given
        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(1);
        given(userService.getCurrentUser()).willReturn(user);
        given(userClubRepository.findByUserAndClub(user, club)).willReturn(Optional.empty());
        given(userClubRepository.save(any(UserClub.class))).willAnswer(inv -> inv.getArgument(0));
        given(clubRepository.incrementMemberCount(1L)).willReturn(1);

        // when
        clubService.joinClub(1L);

        // then
        verify(userClubRepository).save(any(UserClub.class));
        verify(clubRepository).incrementMemberCount(1L);
    }

    @Test
    @DisplayName("정원 초과: 정원이 가득 찬 경우 가입 불가")
    void joinClub_capacityExceeded() {
        // given
        given(clubRepository.findById(1L)).willReturn(Optional.of(club));
        given(userClubRepository.countByClub_ClubId(1L)).willReturn(10); // userLimit = 10

        // when & then
        assertThatThrownBy(() -> clubService.joinClub(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_ENTER);

        verify(userClubRepository, never()).save(any(UserClub.class));
    }
}
