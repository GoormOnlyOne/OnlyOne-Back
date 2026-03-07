package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.document.ClubDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClubElasticsearchRepository extends ElasticsearchRepository<ClubDocument, Long>, ClubElasticsearchRepositoryCustom {

}