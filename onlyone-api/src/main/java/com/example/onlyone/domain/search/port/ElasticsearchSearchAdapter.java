package com.example.onlyone.domain.search.port;

import com.example.onlyone.domain.club.document.ClubDocument;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.engine", havingValue = "elasticsearch", matchIfMissing = true)
public class ElasticsearchSearchAdapter implements SearchPort {

    private final ClubElasticsearchRepository clubElasticsearchRepository;

    @Override
    public List<ClubSearchResult> search(String keyword, String city, String district,
                                         Long interestId, Pageable pageable) {
        List<ClubDocument> documents = clubElasticsearchRepository.search(
                keyword, city, district, interestId, pageable);

        return documents.stream()
                .map(this::toSearchResult)
                .toList();
    }

    private ClubSearchResult toSearchResult(ClubDocument doc) {
        return new ClubSearchResult(
                doc.getClubId(),
                doc.getName(),
                doc.getDescription(),
                doc.getInterestKoreanName(),
                doc.getDistrict(),
                doc.getMemberCount(),
                doc.getClubImage()
        ); // ClubDocument 필드명은 ES 인덱스와 매핑되므로 유지
    }
}
