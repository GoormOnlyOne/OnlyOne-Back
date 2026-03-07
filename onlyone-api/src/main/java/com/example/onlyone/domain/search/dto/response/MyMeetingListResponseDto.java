package com.example.onlyone.domain.search.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MyMeetingListResponseDto(
    @JsonProperty("isUnsettledScheduleExist") boolean unsettledScheduleExists,
    List<ClubResponseDto> clubResponseDtoList
) {
}
