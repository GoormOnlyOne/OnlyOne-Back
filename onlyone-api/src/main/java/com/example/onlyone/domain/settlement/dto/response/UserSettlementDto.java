package com.example.onlyone.domain.settlement.dto.response;

import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;

public record UserSettlementDto(
    Long userId,
    String nickname,
    String profileImage,
    SettlementStatus settlementStatus
) {
    public static UserSettlementDto from(UserSettlement userSettlement) {
        return new UserSettlementDto(
                userSettlement.getUser().getUserId(),
                userSettlement.getUser().getNickname(),
                userSettlement.getUser().getProfileImage(),
                userSettlement.getSettlementStatus()
        );
    }
}
