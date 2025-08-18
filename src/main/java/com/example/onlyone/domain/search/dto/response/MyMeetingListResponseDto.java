package com.example.onlyone.domain.search.dto.response;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class MyMeetingListResponseDto {

    @JsonProperty("isUnsettledScheduleExist")
    private boolean unsettledScheduleExists;
    private List<ClubResponseDto> clubResponseDtoList;
}
