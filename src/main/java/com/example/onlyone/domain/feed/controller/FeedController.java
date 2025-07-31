package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.service.FeedService;
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "feed")
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/feeds")
public class FeedController {
    private final FeedService feedService;

    @Operation(summary = "피드 생성", description = "피드를 생성합니다.")
    @PostMapping
    public ResponseEntity<?> createFeed(@PathVariable("clubId") Long clubId, @RequestBody @Valid FeedRequestDto requestDto) {
        feedService.createFeed(clubId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }

    @Operation(summary = "피드 수정", description = "피드를 수정합니다.")
    @PatchMapping("/{feedId}")
    public ResponseEntity<?> updateFeed(@PathVariable("clubId") Long clubId,
                                        @PathVariable("feedId") Long feedId,
                                        @RequestBody @Valid FeedRequestDto requestDto) {
        feedService.updateFeed(clubId, feedId, requestDto);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
    }

    @Operation(summary = "모임 피드 목록 조회", description = "모임의 피드 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<?> getFeedList(@PathVariable("clubId") Long clubId) {
        List<FeedSummaryResponseDto> feedList = feedService.getFeedList(clubId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(feedList));
    }

    @Operation(summary = "피드 상세 조회", description = "피드를 상세 조회합니다.")
    @GetMapping("/{feedId}")
    public ResponseEntity<?> getFeedDetail(@PathVariable("feedId") Long feedId, @PathVariable("clubId") Long clubId) {
        FeedDetailResponseDto feedDetailResponseDto = feedService.getFeedDetail(clubId, feedId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(feedDetailResponseDto));
    }

}
