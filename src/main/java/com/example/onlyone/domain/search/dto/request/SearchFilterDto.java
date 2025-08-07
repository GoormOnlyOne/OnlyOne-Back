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

    // 검색어 유효성 검증 (키워드는 필수이며 2글자 이상)
    public boolean isKeywordValid() {
        return keyword != null && 
               !keyword.trim().isEmpty() && 
               keyword.trim().length() >= 2;
    }
}