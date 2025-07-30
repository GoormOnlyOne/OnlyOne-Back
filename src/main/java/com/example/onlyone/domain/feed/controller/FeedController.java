package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.service.FeedService;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "feed")
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/feeds")
public class FeedController {
    private final FeedService feedService;

    @Operation(summary = "피드 생성", description = "피드를 생성합니다.")
    @PostMapping
    public ResponseEntity<?> createSchedule(@PathVariable("clubId") Long clubId, @RequestBody @Valid FeedRequestDto requestDto) {
        feedService.createFeed(clubId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }
}
