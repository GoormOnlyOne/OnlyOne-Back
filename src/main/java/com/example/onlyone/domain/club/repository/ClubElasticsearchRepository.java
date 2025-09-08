package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.document.ClubDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClubElasticsearchRepository extends ElasticsearchRepository<ClubDocument, Long> {

    // 키워드만 검색
    @Query("""
        {
          "bool": {
            "must": [
              {
                "multi_match": {
                  "query": "?0",
                  "fields": ["name^2", "description"],
                  "type": "most_fields",
                  "minimum_should_match": "70%"
                }
              }
            ]
          }
        }
        """)
    List<ClubDocument> findByKeyword(String keyword, Pageable pageable);

    // 키워드 + 지역 검색
    @Query("""
        {
          "bool": {
            "must": [
              {
                "multi_match": {
                  "query": "?0",
                  "fields": ["name^2", "description"],
                  "type": "most_fields",
                  "minimum_should_match": "70%"
                }
              },
              {
                "term": { "city": "?1" }
              },
              {
                "term": { "district": "?2" }
              }
            ]
          }
        }
        """)
    List<ClubDocument> findByKeywordAndLocation(String keyword, String city, String district, Pageable pageable);

    // 키워드 + 관심사 검색
    @Query("""
        {
          "bool": {
            "must": [
              {
                "multi_match": {
                  "query": "?0",
                  "fields": ["name^2", "description"],
                  "type": "most_fields",
                  "minimum_should_match": "70%"
                }
              },
              {
                "term": { "interestId": ?1 }
              }
            ]
          }
        }
        """)
    List<ClubDocument> findByKeywordAndInterest(String keyword, Long interestId, Pageable pageable);

    // 키워드 + 지역 + 관심사 검색
    @Query("""
        {
          "bool": {
            "must": [
              {
                "multi_match": {
                  "query": "?0",
                  "fields": ["name^2", "description"],
                  "type": "most_fields",
                  "minimum_should_match": "70%"
                }
              },
              {
                "term": { "city": "?1" }
              },
              {
                "term": { "district": "?2" }
              },
              {
                "term": { "interestId": ?3 }
              }
            ]
          }
        }
        """)
    List<ClubDocument> findByKeywordAndLocationAndInterest(String keyword, String city, String district, Long interestId, Pageable pageable);
}