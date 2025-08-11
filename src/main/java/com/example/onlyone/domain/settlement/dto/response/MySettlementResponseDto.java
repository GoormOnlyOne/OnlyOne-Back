package com.example.onlyone.domain.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class MySettlementResponseDto {
    private int currentPage;
    private int pageSize;
    private int totalPage;
    private long totalElement;
    List<MySettlementDto> mySettlementList;

    public static MySettlementResponseDto from(Page<MySettlementDto> mySettlementList) {
        return MySettlementResponseDto.builder()
                .currentPage(mySettlementList.getNumber())
                .pageSize(mySettlementList.getSize())
                .totalPage(mySettlementList.getTotalPages())
                .totalElement(mySettlementList.getTotalElements())
                .mySettlementList(mySettlementList.getContent())
                .build();
    }
}

