package com.example.onlyone.domain.search.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SearchErrorCode implements ErrorCode {

    INVALID_SEARCH_FILTER(400, "SEARCH_400_1", "지역 필터는 city와 district가 모두 제공되어야 합니다."),
    SEARCH_KEYWORD_TOO_SHORT(400, "SEARCH_400_2", "검색어는 최소 2글자 이상이어야 합니다."),
    INVALID_INTEREST_ID(400, "SEARCH_400_3", "유효하지 않은 interestId입니다."),
    INVALID_LOCATION(400, "SEARCH_400_4", "유효하지 않은 city 또는 district입니다."),

    // Elasticsearch
    ELASTICSEARCH_INDEX_ERROR(500, "ES_500_1", "Elasticsearch 인덱싱 중 오류가 발생했습니다."),
    ELASTICSEARCH_DELETE_ERROR(500, "ES_500_2", "Elasticsearch 삭제 중 오류가 발생했습니다."),
    ELASTICSEARCH_SEARCH_ERROR(500, "ES_500_4", "Elasticsearch 검색 중 오류가 발생했습니다."),
    ELASTICSEARCH_SYNC_ERROR(500, "ES_500_5", "Elasticsearch 동기화 중 오류가 발생했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
