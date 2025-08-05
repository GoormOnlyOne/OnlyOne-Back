package com.example.onlyone.domain.search.controller;

import com.example.onlyone.domain.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Search")
@RequiredArgsConstructor
@RequestMapping("/search")
public class SearchController {
    private final SearchService searchService;

    @Operation(summary = "사용자 맞춤 추천", description = "사용자의 관심사 및 지역 기반으로 모임을 추천합니다.")
    @GetMapping("/recommendations")
    public ResponseEntity<?> recommendedClubs(@RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(searchService.recommendedClubs(page));
    }

    @Operation(summary = "모임 검색 (관심사)", description = "관심사 기반으로 모임을 검색합니다.")
    @GetMapping("/interests")
    public ResponseEntity<?> searchClubByInterest(@RequestParam Long interestId,
                                                  @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(searchService.searchClubByInterest(interestId, page));
    }

    @Operation(summary = "모임 검색 (지역)", description = "지역 기반으로 모임을 검색합니다.")
    @GetMapping("/locations")
    public ResponseEntity<?> searchClubByLocation(@RequestParam String city,
                                                  @RequestParam String district,
                                                  @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(searchService.searchClubByLocation(city, district, page));
    }


    @Operation(summary = "키워드 검색", description = "검색어 기반으로 모임을 검색합니다.")
    @GetMapping("/keywords")
    public ResponseEntity<?> searchClubByKeyword(@RequestParam String keyword, @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(searchService.searchClubByKeyword(keyword, page));
    }
}
