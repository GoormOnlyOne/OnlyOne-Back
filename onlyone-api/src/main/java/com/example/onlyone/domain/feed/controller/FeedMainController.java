package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.request.RefeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.service.FeedCommandService;
import com.example.onlyone.domain.feed.service.FeedCommentService;
import com.example.onlyone.domain.feed.service.FeedQueryService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@Tag(name = "feed-main", description = "전체 피드 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/feeds")
public class FeedMainController {

    private final FeedQueryService feedQueryService;
    private final FeedCommandService feedCommandService;
    private final FeedCommentService feedCommentService;

    @Operation(summary = "최신순 피드 목록 조회", description = "유저와 관련된 모든 피드들을 조회합니다. cursor 파라미터로 커서 기반 페이징 지원.")
    @GetMapping
    public ResponseEntity<CommonResponse<List<FeedOverviewDto>>> getAllFeeds(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100) int page,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "cursor", required = false) Long cursor) {
        Pageable pageable = PageRequest.of(page, limit);
        return ResponseEntity.ok(CommonResponse.success(feedQueryService.getPersonalFeed(pageable, cursor)));
    }

    @Operation(summary = "인기순 피드 목록 조회", description = "전체 피드 목록 조회 기반으로 인기순 페이징 조회")
    @GetMapping("/popular")
    public ResponseEntity<CommonResponse<List<FeedOverviewDto>>> getPopularFeeds(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100) int page,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        Pageable pageable = PageRequest.of(page, limit, Sort.unsorted());
        return ResponseEntity.ok(CommonResponse.success(feedQueryService.getPopularFeed(pageable)));
    }

    @Operation(summary = "댓글 목록 조회", description = "해당 피드에 댓글 목록을 조회합니다.")
    @GetMapping("/{feedId}/comments")
    public ResponseEntity<CommonResponse<List<FeedCommentResponseDto>>> getCommentList(
            @PathVariable Long feedId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100) int page,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        Pageable pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.ASC, "createdAt"));
        return ResponseEntity.ok(CommonResponse.success(feedCommentService.getCommentList(feedId, pageable)));
    }

    @Operation(summary = "리피드", description = "피드를 리피드 합니다.")
    @PostMapping("/{feedId}/{clubId}")
    public ResponseEntity<CommonResponse<Void>> createRefeed(
            @PathVariable Long feedId, @PathVariable Long clubId,
            @RequestBody @Valid RefeedRequestDto requestDto) {
        feedCommandService.createRefeed(feedId, clubId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }
}
