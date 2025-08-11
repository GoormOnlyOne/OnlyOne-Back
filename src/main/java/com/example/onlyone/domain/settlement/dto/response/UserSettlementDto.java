package com.example.onlyone.domain.settlement.dto.response;

import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@AllArgsConstructor
public class UserSettlementDto {
    private Long clubId;
    private Long userId;
    private String nickname;
    private String profileImage;
    private SettlementStatus settlementStatus;

    public static UserSettlementDto from(UserSettlement userSettlement) {
        return UserSettlementDto.builder()
                .clubId(userSettlement.getSettlement().getSchedule().getClub().getClubId())
                .userId(userSettlement.getUser().getUserId())
                .nickname(userSettlement.getUser().getNickname())
                .profileImage(userSettlement.getUser().getProfileImage())
                .settlementStatus(userSettlement.getSettlementStatus())
                .build();
    }
}
