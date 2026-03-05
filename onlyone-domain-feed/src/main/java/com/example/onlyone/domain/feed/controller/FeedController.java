package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.service.FeedCommandService;
import com.example.onlyone.domain.feed.service.FeedCommentService;
import com.example.onlyone.domain.feed.service.FeedLikeToggleService;
import com.example.onlyone.domain.feed.service.FeedQueryService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "feed")
@RequiredArgsConstructor
@RequestMapping("/api/v1/clubs/{clubId}/feeds")
public class FeedController {

    private final FeedCommandService feedCommandService;
    private final FeedQueryService feedQueryService;
    private final FeedLikeToggleService feedLikeService;
    private final FeedCommentService feedCommentService;

    @Operation(summary = "피드 생성", description = "피드를 생성합니다.")
    @PostMapping
    public ResponseEntity<CommonResponse<Void>> createFeed(
            @PathVariable Long clubId, @RequestBody @Valid FeedRequestDto requestDto) {
        feedCommandService.createFeed(clubId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }

    @Operation(summary = "피드 수정", description = "피드를 수정합니다.")
    @PatchMapping("/{feedId}")
    public ResponseEntity<CommonResponse<Void>> updateFeed(
            @PathVariable Long clubId, @PathVariable Long feedId,
            @RequestBody @Valid FeedRequestDto requestDto) {
        feedCommandService.updateFeed(clubId, feedId, requestDto);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "피드 삭제", description = "피드를 삭제합니다.")
    @DeleteMapping("/{feedId}")
    public ResponseEntity<CommonResponse<Void>> deleteFeed(
            @PathVariable Long clubId, @PathVariable Long feedId) {
        feedCommandService.softDeleteFeed(clubId, feedId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "모임 피드 목록 조회", description = "모임의 피드 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<CommonResponse<Page<FeedSummaryResponseDto>>> getFeedList(
            @PathVariable Long clubId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        Pageable pageable = PageRequest.of(page, limit);
        return ResponseEntity.ok(CommonResponse.success(feedQueryService.getFeedList(clubId, pageable)));
    }

    @Operation(summary = "피드 상세 조회", description = "피드를 상세 조회합니다.")
    @GetMapping("/{feedId}")
    public ResponseEntity<CommonResponse<FeedDetailResponseDto>> getFeedDetail(
            @PathVariable Long clubId, @PathVariable Long feedId) {
        return ResponseEntity.ok(CommonResponse.success(feedQueryService.getFeedDetail(clubId, feedId)));
    }

    @Operation(summary = "좋아요 토글", description = "좋아요를 추가하거나 취소합니다.")
    @PutMapping("/{feedId}/likes")
    public ResponseEntity<CommonResponse<Map<String, Boolean>>> toggleLike(
            @PathVariable Long clubId, @PathVariable Long feedId) {
        boolean liked = feedLikeService.toggleLike(clubId, feedId);
        return ResponseEntity.ok(CommonResponse.success(Map.of("liked", liked)));
    }

    @Operation(summary = "댓글 생성", description = "댓글을 생성합니다.")
    @PostMapping("/{feedId}/comments")
    public ResponseEntity<CommonResponse<Void>> createComment(
            @PathVariable Long clubId, @PathVariable Long feedId,
            @RequestBody @Valid FeedCommentRequestDto requestDto) {
        feedCommentService.createComment(clubId, feedId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }

    @Operation(summary = "댓글 삭제", description = "댓글을 삭제합니다.")
    @DeleteMapping("/{feedId}/comments/{commentId}")
    public ResponseEntity<CommonResponse<Void>> deleteComment(
            @PathVariable Long clubId, @PathVariable Long feedId,
            @PathVariable Long commentId) {
        feedCommentService.deleteComment(clubId, feedId, commentId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }
}
