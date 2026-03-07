package com.example.onlyone.domain.search.port;

import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 키워드 full-text 검색 포트.
 * 구현체: ElasticsearchSearchAdapter / MysqlFulltextSearchAdapter
 */
public interface SearchPort {

    List<ClubSearchResult> search(String keyword, String city, String district,
                                  Long interestId, Pageable pageable);
}
