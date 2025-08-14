package com.example.onlyone.domain.search.dto.response;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class MyMeetingListResponseDto {
    private boolean isUnsettledScheduleExist;
    private List<ClubResponseDto> clubResponseDtoList;
}
