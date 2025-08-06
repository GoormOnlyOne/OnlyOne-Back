package com.example.onlyone.domain.search.controller;

import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
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


    @Operation(
        summary = "모임 검색", 
        description = "검색어와 다양한 필터를 적용하여 모임을 검색합니다. " +
                     "keyword는 필수이며 2글자 이상이어야 합니다. " +
                     "지역 필터는 city와 district가 반드시 함께 제공되어야 합니다. (예: city=서울특별시&district=강남구)"
    )
    @GetMapping
    public ResponseEntity<?> searchClubs(
            @RequestParam String keyword,
            @RequestParam(required = false, name = "city") String city,
            @RequestParam(required = false, name = "district") String district,
            @RequestParam(required = false) Long interestId,
            @RequestParam(defaultValue = "MEMBER_COUNT") SearchFilterDto.SortType sortBy,
            @RequestParam(defaultValue = "0") int page) {
        
        SearchFilterDto filter = SearchFilterDto.builder()
                .keyword(keyword)
                .city(city)
                .district(district)
                .interestId(interestId)
                .sortBy(sortBy)
                .page(page)
                .build();
                
        return ResponseEntity.ok(searchService.searchClubs(filter));
    }

    @Operation(summary = "함께하는 멤버들의 다른 모임", description = "내가 속한 모임의 다른 멤버들이 가입한 다른 모임을 조회합니다.")
    @GetMapping("/teammates-clubs")
    public ResponseEntity<?> getClubsByTeammates(@RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(searchService.getClubsByTeammates(page));
    }
}
