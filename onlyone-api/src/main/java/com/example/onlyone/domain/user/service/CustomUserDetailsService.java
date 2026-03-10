package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security UserDetailsService 구현
 *
 * 주의: JWT 인증 시에는 이 서비스를 호출하지 않습니다.
 * JWT 필터에서 토큰 정보만으로 인증 처리합니다.
 * 이 서비스는 폼 로그인이나 특별한 경우에만 사용됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String kakaoId) throws UsernameNotFoundException {
        log.debug("Loading user by kakaoId: {}", kakaoId);

        try {
            Long id = Long.valueOf(kakaoId);
            User user = userRepository.findByKakaoId(id)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found with kakaoId: " + kakaoId));

            return UserPrincipal.from(user);
        } catch (NumberFormatException e) {
            throw new UsernameNotFoundException("Invalid kakaoId format: " + kakaoId);
        }
    }
}
