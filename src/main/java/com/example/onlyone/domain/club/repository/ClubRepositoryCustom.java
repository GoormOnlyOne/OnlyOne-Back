package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.search.dto.request.SearchFilterDto;
import java.util.List;

public interface ClubRepositoryCustom {
    List<Object[]> searchByKeywordWithFilter(SearchFilterDto filter, int page, int size);
}