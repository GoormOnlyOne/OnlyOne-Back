package com.example.onlyone.domain.settlement.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record SettlementResponseDto(
    int currentPage,
    int pageSize,
    int totalPage,
    long totalElement,
    List<UserSettlementDto> userSettlementList
) {
    public static SettlementResponseDto from(Page<UserSettlementDto> userSettlementList) {
        return new SettlementResponseDto(
                userSettlementList.getNumber(),
                userSettlementList.getSize(),
                userSettlementList.getTotalPages(),
                userSettlementList.getTotalElements(),
                userSettlementList.getContent()
        );
    }
}
