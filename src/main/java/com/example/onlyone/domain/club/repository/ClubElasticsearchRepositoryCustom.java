package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.document.ClubDocument;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ClubElasticsearchRepositoryCustom {
    List<ClubDocument> findByKeyword(String keyword, Pageable pageable);
    List<ClubDocument> findByKeywordAndLocation(String keyword, String city,
                                                String district, Pageable pageable);
    List<ClubDocument> findByKeywordAndInterest(String keyword, Long
            interestId, Pageable pageable);
    List<ClubDocument> findByKeywordAndLocationAndInterest(String keyword,
                                                           String city, String district, Long interestId, Pageable pageable);
}
