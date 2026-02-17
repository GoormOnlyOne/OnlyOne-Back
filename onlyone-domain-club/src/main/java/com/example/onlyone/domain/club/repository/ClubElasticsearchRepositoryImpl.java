package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.document.ClubDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.SourceFilter;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.stereotype.Repository;
import co.elastic.clients.elasticsearch._types.query_dsl.*;

import java.util.List;

@Repository
@RequiredArgsConstructor
@ConditionalOnBean(ElasticsearchOperations.class)
public class ClubElasticsearchRepositoryImpl implements ClubElasticsearchRepositoryCustom {

    private final ElasticsearchOperations elasticsearchOperations;

    private static final String[] SOURCE_INCLUDES = {
            "clubId", "name", "description", "city", "district",
            "clubImage", "memberCount", "interestId", "interestCategory",
            "interestKoreanName"
            // createdAt 제외: 정렬은 ES 내부에서 처리, _source에 불필요
            // (LocalDateTime 변환 오류 방지)
    };

    /**
     * Unified dynamic search — keyword in must (scoring), filters in filter context (cached, no scoring).
     * _source filtering reduces data transfer.
     */
    @Override
    public List<ClubDocument> search(String keyword, String city, String district, Long interestId, Pageable pageable) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q
                        .bool(b -> {
                            // keyword → must (scoring)
                            b.must(m -> m
                                    .multiMatch(mm -> mm
                                            .query(keyword)
                                            .fields("name^2.0", "description")
                                            .type(TextQueryType.MostFields)
                                            .minimumShouldMatch("50%")
                                    )
                            );
                            // exact-match filters → filter context (cached, no scoring overhead)
                            if (city != null && !city.isBlank()) {
                                b.filter(f -> f.term(t -> t.field("city").value(city)));
                            }
                            if (district != null && !district.isBlank()) {
                                b.filter(f -> f.term(t -> t.field("district").value(district)));
                            }
                            if (interestId != null) {
                                b.filter(f -> f.term(t -> t.field("interestId").value(interestId)));
                            }
                            return b;
                        })
                )
                .withPageable(pageable)
                .withTrackTotalHits(false)
                .withRequestCache(true)
                .withSourceFilter(new FetchSourceFilter(true, SOURCE_INCLUDES, null))
                .build();

        SearchHits<ClubDocument> searchHits = elasticsearchOperations.search(query, ClubDocument.class);
        return searchHits.stream().map(SearchHit::getContent).toList();
    }

    // ── Legacy methods (delegate to unified search) ──

    @Override
    public List<ClubDocument> findByKeyword(String keyword, Pageable pageable) {
        return search(keyword, null, null, null, pageable);
    }

    @Override
    public List<ClubDocument> findByKeywordAndLocation(String keyword, String city, String district, Pageable pageable) {
        return search(keyword, city, district, null, pageable);
    }

    @Override
    public List<ClubDocument> findByKeywordAndInterest(String keyword, Long interestId, Pageable pageable) {
        return search(keyword, null, null, interestId, pageable);
    }

    @Override
    public List<ClubDocument> findByKeywordAndLocationAndInterest(String keyword, String city, String district, Long interestId, Pageable pageable) {
        return search(keyword, city, district, interestId, pageable);
    }
}
