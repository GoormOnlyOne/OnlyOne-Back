package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Builder
@Getter
@AllArgsConstructor
public class MySettlementDto {
    private Long clubId;
    private int amount;
    private String mainImage;
    private SettlementStatus settlementStatus;
    private String title;
    private LocalDateTime createdAt;

//    public static MySettlementDto from(Page<MySettlementDto> userSettlement) {
//        return MySettlementDto.builder()
//                .clubId(userSettlement.getSettlement().getSchedule().getClub().getClubId())
//                .settlementId(userSettlement.getSettlement().getSettlementId())
//                .amount(userSettlement.getSettlement().getSchedule().getCost())
//                .mainImage(userSettlement.getSettlement().getSchedule().getClub().getClubImage())
//                .build();
//    }
}
