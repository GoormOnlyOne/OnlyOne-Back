package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.document.ClubDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.stereotype.Repository;
import co.elastic.clients.elasticsearch._types.query_dsl.*;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ClubElasticsearchRepositoryImpl implements ClubElasticsearchRepositoryCustom {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public List<ClubDocument> findByKeyword(String keyword, Pageable pageable) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> b
                                .must(m -> m
                                        .multiMatch(mm -> mm
                                                .query(keyword)
                                                .fields("name^2.0", "description")
                                                .type(TextQueryType.MostFields)
                                                .minimumShouldMatch("70%")
                                        )
                                )
                        )
                )
                .withPageable(pageable)
                .withTrackTotalHits(false)
                .build();

        SearchHits<ClubDocument> searchHits = elasticsearchOperations.search(query, ClubDocument.class);
        return searchHits.stream().map(SearchHit::getContent).toList();
    }

    @Override
    public List<ClubDocument> findByKeywordAndLocation(String keyword, String city, String district, Pageable pageable) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> b
                                .must(m -> m
                                        .multiMatch(mm -> mm
                                                .query(keyword)
                                                .fields("name^2.0", "description")
                                                .type(TextQueryType.MostFields)
                                                .minimumShouldMatch("70%")
                                        )
                                )
                                .must(m -> m
                                        .term(t -> t
                                                .field("city.keyword")
                                                .value(city)
                                        )
                                )
                                .must(m -> m
                                        .term(t -> t
                                                .field("district.keyword")
                                                .value(district)
                                        )
                                )
                        )
                )
                .withPageable(pageable)
                .withTrackTotalHits(false)
                .build();

        SearchHits<ClubDocument> searchHits = elasticsearchOperations.search(query, ClubDocument.class);
        return searchHits.stream().map(SearchHit::getContent).toList();
    }

    @Override
    public List<ClubDocument> findByKeywordAndInterest(String keyword, Long interestId, Pageable pageable) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> b
                                .must(m -> m
                                        .multiMatch(mm -> mm
                                                .query(keyword)
                                                .fields("name^2.0", "description")
                                                .type(TextQueryType.MostFields)
                                                .minimumShouldMatch("70%")
                                        )
                                )
                                .must(m -> m
                                        .term(t -> t
                                                .field("interestId")
                                                .value(interestId)
                                        )
                                )
                        )
                )
                .withPageable(pageable)
                .withTrackTotalHits(false)
                .build();

        SearchHits<ClubDocument> searchHits = elasticsearchOperations.search(query, ClubDocument.class);
        return searchHits.stream().map(SearchHit::getContent).toList();
    }

    @Override
    public List<ClubDocument> findByKeywordAndLocationAndInterest(String keyword, String city, String district, Long interestId, Pageable pageable) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> b
                                .must(m -> m
                                        .multiMatch(mm -> mm
                                                .query(keyword)
                                                .fields("name^2.0", "description")
                                                .type(TextQueryType.MostFields)
                                                .minimumShouldMatch("70%")
                                        )
                                )
                                .must(m -> m
                                        .term(t -> t
                                                .field("city.keyword")
                                                .value(city)
                                        )
                                )
                                .must(m -> m
                                        .term(t -> t
                                                .field("district.keyword")
                                                .value(district)
                                        )
                                )
                                .must(m -> m
                                        .term(t -> t
                                                .field("interestId")
                                                .value(interestId)
                                        )
                                )
                        )
                )
                .withPageable(pageable)
                .withTrackTotalHits(false)
                .build();

        SearchHits<ClubDocument> searchHits = elasticsearchOperations.search(query, ClubDocument.class);
        return searchHits.stream().map(SearchHit::getContent).toList();
    }
}