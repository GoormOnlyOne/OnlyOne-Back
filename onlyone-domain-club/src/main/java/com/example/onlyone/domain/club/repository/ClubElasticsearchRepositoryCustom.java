package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.document.ClubDocument;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ClubElasticsearchRepositoryCustom {

    /**
     * Unified dynamic search: keyword (must) + optional filters (filter context).
     */
    List<ClubDocument> search(String keyword, String city, String district, Long interestId, Pageable pageable);
}
