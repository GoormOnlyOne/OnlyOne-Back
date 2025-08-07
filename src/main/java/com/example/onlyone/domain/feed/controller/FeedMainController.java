package com.example.onlyone.domain.feed.controller;

import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.service.FeedMainService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "feed-main", description = "전체 피드 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/feeds")
public class FeedMainController {
    private final FeedMainService feedMainService;

    @Operation(summary = "전체 피드 목록 조회", description = "유저와 관련된 모든 피드들을 조회합니다.")
    @GetMapping
    public ResponseEntity<?> getAllFeeds(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        Pageable pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<FeedOverviewDto> feeds = feedMainService.getPersonalFeed(pageable);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(feeds));
    }

    @Operation(summary = "인기순 피드 목록 조회", description = "전체 피드 목록 조회 기반으로 인기순 페이징 조회")
    @GetMapping("/popular")
    public ResponseEntity<?> getPopularFeeds(
            @RequestParam(name = "page", defaultValue = "0")  int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        Pageable pageable = PageRequest.of(page, limit, Sort.unsorted());
        List<FeedOverviewDto> popularFeeds = feedMainService.getPopularFeed(pageable);
        return ResponseEntity.ok(CommonResponse.success(popularFeeds));
    }
}

