package com.example.onlyone.domain.search.dto.request;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchFilterDto {
    private String keyword;
    private String city;
    private String district;
    private Long interestId;
    private SortType sortBy;
    private int page;

    public enum SortType {
        LATEST("최신순"),
        MEMBER_COUNT("멤버 많은 순");

        private final String description;

        SortType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public int getPage() {
        return Math.max(0, page);
    }

    public SortType getSortBy() {
        return sortBy != null ? sortBy : SortType.MEMBER_COUNT;
    }

    // 지역 필터 유효성 검증 (city와 district는 세트)
    public boolean isLocationValid() {
        if (city == null && district == null) {
            return true; // 둘 다 없으면 OK
        }
        if (city != null && district != null && 
            !city.trim().isEmpty() && !district.trim().isEmpty()) {
            return true; // 둘 다 있으면 OK
        }
        return false; // 하나만 있으면 Invalid
    }
    
    public boolean hasLocation() {
        return city != null && district != null && 
               !city.trim().isEmpty() && !district.trim().isEmpty();
    }

    // 검색어 유효성 검증 (키워드가 있으면 2글자 이상이어야 함)
    public boolean isKeywordValid() {
        // 키워드가 없으면 항상 허용 (전체 모임 조회)
        if (keyword == null || keyword.trim().isEmpty()) {
            return true;
        }
        // 키워드가 있으면 2글자 이상이어야 함
        return keyword.trim().length() >= 2;
    }
    
    // 필터 조건이 있는지 확인
    public boolean hasFilter() {
        return hasLocation() || interestId != null;
    }
    
    // 키워드가 있는지 확인
    public boolean hasKeyword() {
        return keyword != null && !keyword.trim().isEmpty();
    }
}