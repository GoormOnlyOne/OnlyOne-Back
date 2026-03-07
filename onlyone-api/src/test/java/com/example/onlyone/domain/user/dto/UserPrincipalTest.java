package com.example.onlyone.domain.user.dto;

import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Role;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDate;
import java.util.Collection;

import static org.assertj.core.api.Assertions.*;

/**
 * UserPrincipal 테스트
 * - Spring Security UserDetails 구현 검증
 * - Factory 메서드 검증
 * - 권한 및 상태 검증
 */
class UserPrincipalTest {

    @Test
    @DisplayName("User 엔티티로부터 UserPrincipal 생성")
    void from_userEntity_success() {
        // given
        User user = createTestUser(Status.ACTIVE, Role.ROLE_USER);

        // when
        UserPrincipal principal = UserPrincipal.from(user);

        // then
        assertThat(principal.getUserId()).isEqualTo(user.getUserId());
        assertThat(principal.getKakaoId()).isEqualTo(user.getKakaoId());
        assertThat(principal.getNickname()).isEqualTo(user.getNickname());
        assertThat(principal.getStatus()).isEqualTo(user.getStatus());
        assertThat(principal.getRole()).isEqualTo(user.getRole());
    }

    @Test
    @DisplayName("JWT Claims로부터 UserPrincipal 생성")
    void fromClaims_success() {
        // given
        String userId = "1";
        String kakaoId = "12345";
        String status = "ACTIVE";
        String role = "ROLE_USER";

        // when
        UserPrincipal principal = UserPrincipal.fromClaims(userId, kakaoId, status, role);

        // then
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getKakaoId()).isEqualTo(12345L);
        assertThat(principal.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(principal.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(principal.getNickname()).isNull(); // JWT에는 닉네임 없음
    }

    @Test
    @DisplayName("ACTIVE 사용자 - 계정 활성화 상태")
    void isEnabled_activeUser_true() {
        // given
        User user = createTestUser(Status.ACTIVE, Role.ROLE_USER);
        UserPrincipal principal = UserPrincipal.from(user);

        // when & then
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("INACTIVE 사용자 - 계정 비활성화 상태")
    void isEnabled_inactiveUser_false() {
        // given
        User user = createTestUser(Status.INACTIVE, Role.ROLE_USER);
        UserPrincipal principal = UserPrincipal.from(user);

        // when & then
        assertThat(principal.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("GUEST 사용자 - 계정 활성화 상태")
    void isEnabled_guestUser_true() {
        // given
        User user = createTestUser(Status.GUEST, Role.ROLE_USER);
        UserPrincipal principal = UserPrincipal.from(user);

        // when & then
        assertThat(principal.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("ROLE_USER 권한 확인")
    void getAuthorities_roleUser() {
        // given
        User user = createTestUser(Status.ACTIVE, Role.ROLE_USER);
        UserPrincipal principal = UserPrincipal.from(user);

        // when
        Collection<? extends GrantedAuthority> authorities = principal.getAuthorities();

        // then
        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("ROLE_ADMIN 권한 확인")
    void getAuthorities_roleAdmin() {
        // given
        User user = createTestUser(Status.ACTIVE, Role.ROLE_ADMIN);
        UserPrincipal principal = UserPrincipal.from(user);

        // when
        Collection<? extends GrantedAuthority> authorities = principal.getAuthorities();

        // then
        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("getUsername은 kakaoId 문자열 반환")
    void getUsername_returnsKakaoId() {
        // given
        User user = createTestUser(Status.ACTIVE, Role.ROLE_USER);
        UserPrincipal principal = UserPrincipal.from(user);

        // when
        String username = principal.getUsername();

        // then
        assertThat(username).isEqualTo(String.valueOf(user.getKakaoId()));
    }

    @Test
    @DisplayName("getPassword는 항상 null 반환 (OAuth 사용)")
    void getPassword_returnsNull() {
        // given
        User user = createTestUser(Status.ACTIVE, Role.ROLE_USER);
        UserPrincipal principal = UserPrincipal.from(user);

        // when
        String password = principal.getPassword();

        // then
        assertThat(password).isNull();
    }

    @Test
    @DisplayName("다양한 Status와 Role 조합 테스트")
    void variousCombinations() {
        // ACTIVE + ADMIN
        UserPrincipal activeAdmin = UserPrincipal.from(createTestUser(Status.ACTIVE, Role.ROLE_ADMIN));
        assertThat(activeAdmin.isEnabled()).isTrue();
        assertThat(activeAdmin.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");

        // GUEST + USER
        UserPrincipal guestUser = UserPrincipal.from(createTestUser(Status.GUEST, Role.ROLE_USER));
        assertThat(guestUser.isEnabled()).isTrue();
        assertThat(guestUser.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");

        // INACTIVE + ADMIN (탈퇴한 관리자)
        UserPrincipal inactiveAdmin = UserPrincipal.from(createTestUser(Status.INACTIVE, Role.ROLE_ADMIN));
        assertThat(inactiveAdmin.isEnabled()).isFalse();
        assertThat(inactiveAdmin.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("JWT Claims 파싱 - 다양한 값")
    void fromClaims_variousValues() {
        // ADMIN 권한
        UserPrincipal admin = UserPrincipal.fromClaims("1", "12345", "ACTIVE", "ROLE_ADMIN");
        assertThat(admin.getRole()).isEqualTo(Role.ROLE_ADMIN);

        // GUEST 상태
        UserPrincipal guest = UserPrincipal.fromClaims("2", "67890", "GUEST", "ROLE_USER");
        assertThat(guest.getStatus()).isEqualTo(Status.GUEST);
        assertThat(guest.isEnabled()).isTrue();

        // INACTIVE 상태
        UserPrincipal inactive = UserPrincipal.fromClaims("3", "11111", "INACTIVE", "ROLE_USER");
        assertThat(inactive.getStatus()).isEqualTo(Status.INACTIVE);
        assertThat(inactive.isEnabled()).isFalse();
    }

    // 헬퍼 메서드

    private User createTestUser(Status status, Role role) {
        return User.builder()
                .kakaoId(12345L)
                .nickname("테스트유저")
                .birth(LocalDate.of(1990, 1, 1))
                .gender(Gender.MALE)
                .status(status)
                .role(role)
                .build();
    }
}
