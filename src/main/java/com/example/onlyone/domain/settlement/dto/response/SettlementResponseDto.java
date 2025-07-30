package com.example.onlyone.domain.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class SettlementResponseDto {
    private int currentPage;
    private int pageSize;
    private int totalPage;
    private long totalElement;
    List<UserSettlementDto> userSettlementList;

    public static SettlementResponseDto from(Page<UserSettlementDto> userSettlementList) {
        return SettlementResponseDto.builder()
                .currentPage(userSettlementList.getNumber())
                .pageSize(userSettlementList.getSize())
                .totalPage(userSettlementList.getTotalPages())
                .totalElement(userSettlementList.getTotalElements())
                .userSettlementList(userSettlementList.getContent())
                .build();
    }
}
