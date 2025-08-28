package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.dto.response.MySettlementDto;
import com.example.onlyone.domain.user.dto.response.MySettlementResponseDto;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@DataJpaTest
@Import(UserService.class)
class UserServiceTest {

    @Autowired
    UserService userService;
    @Autowired
    UserRepository userRepository;
    @MockBean
    UserSettlementRepository userSettlementRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.findById(1L).orElseThrow();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                user.getKakaoId().toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void 유저의_최근_정산목록이_정상적으로_조회된다() {
        // given
        Pageable pageable = PageRequest.of(0, 5);
        MySettlementDto dto = new MySettlementDto(
                1L,
                1L,
                10000,
                null,
                SettlementStatus.COMPLETED,
                "유저의 모임: 첫 번째 정기모임",
                LocalDateTime.now().minusDays(1)
        );
        Page<MySettlementDto> mockPage =
                new PageImpl<>(List.of(dto), pageable, 1);

        when(userSettlementRepository.findMyRecentOrRequested(
                eq(user), any(LocalDateTime.class), eq(pageable))
        ).thenReturn(mockPage);
        // when
        MySettlementResponseDto response = userService.getMySettlementList(pageable);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getMySettlementList()).hasSize(1);
        assertThat(response.getMySettlementList().get(0).getTitle())
                .isEqualTo("유저의 모임: 첫 번째 정기모임");
        assertThat(response.getMySettlementList().get(0).getAmount())
                .isEqualTo(10000);
        assertThat(response.getMySettlementList().get(0).getSettlementStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }
}
