package com.example.onlyone.domain.search.port;

/**
 * ES/MySQL FULLTEXT 공통 검색 결과 DTO.
 * 어댑터가 엔진별 결과를 이 record로 변환하여 반환한다.
 */
public record ClubSearchResult(
        Long clubId,
        String name,
        String description,
        String interest,
        String district,
        Long memberCount,
        String image
) {}
