package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static com.example.onlyone.domain.settlement.fixture.FinanceFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementQueryService 단위 테스트")
class SettlementQueryServiceTest {

    @InjectMocks private SettlementQueryService settlementQueryService;

    @Mock private SettlementRepository settlementRepository;
    @Mock private UserSettlementRepository userSettlementRepository;

    @Test
    @DisplayName("성공: 스케줄 참여자 정산 목록을 페이징으로 조회한다")
    void success() {
        // given
        User leader = leader();
        Settlement settlement = settlement(leader);
        Pageable pageable = PageRequest.of(0, 10);

        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.of(settlement));

        List<UserSettlementDto> dtos = List.of(
                new UserSettlementDto(2L, "Bob", null, SettlementStatus.HOLD_ACTIVE),
                new UserSettlementDto(3L, "Charlie", null, SettlementStatus.HOLD_ACTIVE)
        );
        Page<UserSettlementDto> page = new PageImpl<>(dtos, pageable, 2);
        given(userSettlementRepository.findAllDtoBySettlement(settlement, pageable)).willReturn(page);

        // when
        SettlementResponseDto result = settlementQueryService.getSettlementList(SCHEDULE_ID, pageable);

        // then
        assertThat(result).isNotNull();
        assertThat(result.userSettlementList()).hasSize(2);
        assertThat(result.currentPage()).isZero();
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.totalElement()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패: 조회 시 정산이 없으면 SETTLEMENT_NOT_FOUND")
    void settlementNotFound() {
        given(settlementRepository.findByScheduleId(SCHEDULE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementQueryService.getSettlementList(SCHEDULE_ID, PageRequest.of(0, 10)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(FinanceErrorCode.SETTLEMENT_NOT_FOUND);
    }
}
