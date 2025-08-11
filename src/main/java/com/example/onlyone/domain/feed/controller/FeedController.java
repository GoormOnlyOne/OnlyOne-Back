package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.request.FeedCommentRequestDto;
import com.example.onlyone.domain.feed.dto.request.FeedRequestDto;
import com.example.onlyone.domain.feed.dto.response.FeedDetailResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.service.FeedService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    @Operation(summary = "피드 삭제", description = "피드를 삭제합니다.")
    @DeleteMapping("/{feedId}")
    public ResponseEntity<?> deleteFeed(@PathVariable("clubId") Long clubId, @PathVariable("feedId") Long feedId) {
        feedService.deleteFeed(clubId, feedId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
    }

    @Operation(summary = "모임 피드 목록 조회", description = "모임의 피드 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<?> getFeedList(@PathVariable("clubId") Long clubId,
                                         @RequestParam(name = "page", defaultValue = "0") int page,
                                         @RequestParam(name = "limit", defaultValue = "20") int limit) {
        Pageable pageable = PageRequest.of(page, limit, Sort.by("createdAt").descending());
        Page<FeedSummaryResponseDto> feedList = feedService.getFeedList(clubId, pageable);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(feedList));
    }

    @Operation(summary = "피드 상세 조회", description = "피드를 상세 조회합니다.")
    @GetMapping("/{feedId}")
    public ResponseEntity<?> getFeedDetail(@PathVariable("clubId") Long clubId, @PathVariable("feedId") Long feedId) {
        FeedDetailResponseDto feedDetailResponseDto = feedService.getFeedDetail(clubId, feedId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(feedDetailResponseDto));
    }

    @Operation(summary = "좋아요 토글", description = "좋아요를 추가하거나 취소합니다.")
    @PutMapping("/{feedId}/likes")
    public ResponseEntity<?> toggleLike(@PathVariable("clubId") Long clubId, @PathVariable("feedId") Long feedId) {
        boolean liked = feedService.toggleLike(clubId, feedId);

        if (liked) {
            return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
        } else {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body(null);
        }
    }

    @Operation(summary = "댓글 생성", description = "댓글을 생성합니다.")
    @PostMapping("/{feedId}/comments")
    public ResponseEntity<?> createComment(@PathVariable("clubId") Long clubId,
                                           @PathVariable("feedId") Long feedId,
                                           @RequestBody @Valid FeedCommentRequestDto requestDto) {
        feedService.createComment(clubId, feedId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }

    @Operation(summary = "댓글 삭제", description = "댓글을 삭제합니다.")
    @DeleteMapping("/{feedId}/comments/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable("clubId") Long clubId,
                                           @PathVariable("feedId") Long feedId,
                                           @PathVariable("commentId") Long commentId) {
        feedService.deleteComment(clubId, feedId, commentId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
    }
}
