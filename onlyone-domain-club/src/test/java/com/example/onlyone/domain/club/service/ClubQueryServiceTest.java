package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.example.onlyone.test.ClubFixtures.*;
import static com.example.onlyone.test.UserFixtures.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClubQueryService 단위 테스트")
class ClubQueryServiceTest {

    @InjectMocks
    private ClubQueryService clubQueryService;

    @Mock private ClubRepository clubRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;

    @Nested
    @DisplayName("모임 상세 조회")
    class GetClubDetail {

        @Test
        @DisplayName("성공: 가입된 회원이면 해당 역할로 조회된다")
        void getClubDetail_member() {
            // given
            Club club = aClub().build();
            User user = aUser(1L).build();
            UserClub userClub = aUserClub(user, club, ClubRole.MEMBER).userClubId(1L).build();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club)).willReturn(Optional.of(userClub));
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(5);

            // when
            ClubDetailResponseDto result = clubQueryService.getClubDetail(1L);

            // then
            assertThat(result.clubId()).isEqualTo(1L);
            assertThat(result.clubRole()).isEqualTo(ClubRole.MEMBER);
            assertThat(result.userCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("성공: 미가입 회원이면 GUEST 역할로 조회된다")
        void getClubDetail_guest() {
            // given
            Club club = aClub().build();
            User user = aUser(2L).build();

            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userService.getCurrentUser()).willReturn(user);
            given(userClubRepository.findByUserAndClub(user, club)).willReturn(Optional.empty());
            given(userClubRepository.countByClub_ClubId(1L)).willReturn(3);

            // when
            ClubDetailResponseDto result = clubQueryService.getClubDetail(1L);

            // then
            assertThat(result.clubId()).isEqualTo(1L);
            assertThat(result.clubRole()).isEqualTo(ClubRole.GUEST);
            assertThat(result.userCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("실패: 모임이 존재하지 않으면 CLUB_NOT_FOUND")
        void getClubDetail_fail_clubNotFound() {
            // given
            given(clubRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> clubQueryService.getClubDetail(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.CLUB_NOT_FOUND);
        }
    }
}
