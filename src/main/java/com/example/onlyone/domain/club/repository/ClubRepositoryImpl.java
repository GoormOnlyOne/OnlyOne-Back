package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.search.dto.request.SearchFilterDto;

import java.util.List;

public class ClubRepositoryImpl implements ClubRepositoryCustom {

    // 통합검색
    @Override
    public List<Object[]> searchByKeywordWithFilter(SearchFilterDto filter, int page, int size) {
        return List.of();
    }
}
