package com.example.onlyone.domain.user.dto;

import com.example.onlyone.domain.user.entity.Role;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security UserDetails 구현체
 *
 * User 엔티티를 래핑하여 인증/인가 정보를 제공합니다.
 * 도메인 엔티티와 Security 프레임워크를 분리합니다.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final Long kakaoId;
    private final String nickname;
    private final Status status;
    private final Role role;

    private UserPrincipal(Long userId, Long kakaoId, String nickname, Status status, Role role) {
        this.userId = userId;
        this.kakaoId = kakaoId;
        this.nickname = nickname;
        this.status = status;
        this.role = role;
    }

    /**
     * User 엔티티로부터 UserPrincipal 생성
     */
    public static UserPrincipal from(User user) {
        return new UserPrincipal(
                user.getUserId(),
                user.getKakaoId(),
                user.getNickname(),
                user.getStatus(),
                user.getRole()
        );
    }

    /**
     * JWT 클레임으로부터 UserPrincipal 생성 (DB 조회 없이)
     */
    public static UserPrincipal fromClaims(String userId, String kakaoId, String status, String role) {
        return new UserPrincipal(
                Long.valueOf(userId),
                Long.valueOf(kakaoId),
                null,  // JWT에 nickname 없을 수 있음
                Status.valueOf(status),
                Role.valueOf(role)
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(role.name()));
    }

    @Override
    public String getPassword() {
        // 카카오 OAuth 사용으로 비밀번호 없음
        return null;
    }

    @Override
    public String getUsername() {
        // kakaoId를 username으로 사용
        return String.valueOf(kakaoId);
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        // INACTIVE 상태가 아니면 활성화
        return status != Status.INACTIVE;
    }

    public boolean isGuest() {
        return status == Status.GUEST;
    }

    @Override
    public String toString() {
        return String.format("UserPrincipal{userId=%d, kakaoId=%d, status=%s, role=%s}",
                userId, kakaoId, status, role);
    }
}
