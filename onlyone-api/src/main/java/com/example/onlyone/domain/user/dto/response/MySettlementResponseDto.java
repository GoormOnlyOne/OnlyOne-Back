package com.example.onlyone.domain.user.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record MySettlementResponseDto(
    int currentPage,
    int pageSize,
    int totalPage,
    long totalElement,
    List<MySettlementDto> mySettlementList
) {
    public static MySettlementResponseDto from(Page<MySettlementDto> mySettlementList) {
        return new MySettlementResponseDto(
                mySettlementList.getNumber(),
                mySettlementList.getSize(),
                mySettlementList.getTotalPages(),
                mySettlementList.getTotalElements(),
                mySettlementList.getContent()
        );
    }
}
