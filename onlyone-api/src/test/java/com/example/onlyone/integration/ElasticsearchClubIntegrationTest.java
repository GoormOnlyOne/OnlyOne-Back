package com.example.onlyone.integration;

import com.example.onlyone.domain.club.document.ClubDocument;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.example.onlyone.support.AbstractElasticsearchContainerTest;
import com.example.onlyone.support.ElasticsearchTestConfig;
import com.example.onlyone.support.IntegrationTestConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elasticsearch Club 인덱싱/검색 통합 테스트.
 * nori 분석 플러그인이 설치된 실제 ES 컨테이너에서 ClubDocument의 CRUD와 검색 쿼리를 검증한다.
 */
@SpringBootTest
@Import({IntegrationTestConfig.class, ElasticsearchTestConfig.class})
@DisplayName("Elasticsearch Club 통합 테스트")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElasticsearchClubIntegrationTest extends AbstractElasticsearchContainerTest {

    @Autowired
    private ClubElasticsearchRepository clubElasticsearchRepository;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    private static final Pageable PAGE = PageRequest.of(0, 10);

    @BeforeEach
    void setUp() {
        clubElasticsearchRepository.deleteAll();
        // ES 인덱스 리프레시 (즉시 검색 가능하도록)
        elasticsearchOperations.indexOps(ClubDocument.class).refresh();

        // 테스트 데이터 삽입
        clubElasticsearchRepository.saveAll(List.of(
                ClubDocument.builder()
                        .clubId(1L)
                        .name("서울 축구 동호회")
                        .description("매주 토요일 축구를 즐기는 모임입니다")
                        .city("서울")
                        .district("강남구")
                        .memberCount(25L)
                        .interestId(1L)
                        .interestCategory("SPORTS")
                        .interestKoreanName("스포츠")
                        .searchText("서울 축구 동호회 매주 토요일 축구를 즐기는 모임입니다")
                        .createdAt(null)
                        .build(),
                ClubDocument.builder()
                        .clubId(2L)
                        .name("강남 독서 클럽")
                        .description("함께 책을 읽고 토론하는 모임")
                        .city("서울")
                        .district("강남구")
                        .memberCount(15L)
                        .interestId(2L)
                        .interestCategory("CULTURE")
                        .interestKoreanName("문화")
                        .searchText("강남 독서 클럽 함께 책을 읽고 토론하는 모임")
                        .createdAt(null)
                        .build(),
                ClubDocument.builder()
                        .clubId(3L)
                        .name("부산 러닝 크루")
                        .description("해운대에서 달리기를 즐기는 크루")
                        .city("부산")
                        .district("해운대구")
                        .memberCount(30L)
                        .interestId(1L)
                        .interestCategory("SPORTS")
                        .interestKoreanName("스포츠")
                        .searchText("부산 러닝 크루 해운대에서 달리기를 즐기는 크루")
                        .createdAt(null)
                        .build()
        ));

        // 인덱싱 후 리프레시하여 즉시 검색 가능하게
        elasticsearchOperations.indexOps(ClubDocument.class).refresh();
    }

    @Test
    @Order(1)
    @DisplayName("클럽을 인덱싱하면 ES에서 조회할 수 있다")
    void indexClub_canBeRetrieved() {
        // when
        Optional<ClubDocument> found = clubElasticsearchRepository.findById(1L);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("서울 축구 동호회");
        assertThat(found.get().getCity()).isEqualTo("서울");
    }

    @Test
    @Order(2)
    @DisplayName("키워드로 검색하면 관련 클럽이 반환된다")
    void searchByKeyword_returnsMatchingClubs() {
        // when
        List<ClubDocument> results = clubElasticsearchRepository.search("축구", null, null, null, PAGE);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(doc -> doc.getName().contains("축구"));
    }

    @Test
    @Order(3)
    @DisplayName("키워드와 지역으로 필터 검색이 동작한다")
    void searchByKeywordAndLocation_returnsFilteredClubs() {
        // when
        List<ClubDocument> results = clubElasticsearchRepository
                .search("축구", "서울", "강남구", null, PAGE);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(doc -> "서울".equals(doc.getCity()));
        assertThat(results).allMatch(doc -> "강남구".equals(doc.getDistrict()));
    }

    @Test
    @Order(4)
    @DisplayName("키워드와 관심사로 필터 검색이 동작한다")
    void searchByKeywordAndInterest_returnsFilteredClubs() {
        // when: interestId=1 (SPORTS) 필터
        List<ClubDocument> results = clubElasticsearchRepository
                .search("축구", null, null, 1L, PAGE);

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(doc -> doc.getInterestId().equals(1L));
    }

    @Test
    @Order(5)
    @DisplayName("클럽을 삭제하면 ES에서 조회되지 않는다")
    void deleteClub_notFoundInEs() {
        // when
        clubElasticsearchRepository.deleteById(1L);
        elasticsearchOperations.indexOps(ClubDocument.class).refresh();

        // then
        Optional<ClubDocument> found = clubElasticsearchRepository.findById(1L);
        assertThat(found).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("클럽을 업데이트하면 변경된 내용이 반영된다")
    void updateClub_changesReflected() {
        // given
        Optional<ClubDocument> original = clubElasticsearchRepository.findById(2L);
        assertThat(original).isPresent();

        // when: 이름 변경
        ClubDocument updated = ClubDocument.builder()
                .clubId(2L)
                .name("강남 프리미엄 독서 클럽")
                .description(original.get().getDescription())
                .city(original.get().getCity())
                .district(original.get().getDistrict())
                .memberCount(20L)
                .interestId(original.get().getInterestId())
                .interestCategory(original.get().getInterestCategory())
                .interestKoreanName(original.get().getInterestKoreanName())
                .searchText("강남 프리미엄 독서 클럽 " + original.get().getDescription())
                .createdAt(original.get().getCreatedAt())
                .build();
        clubElasticsearchRepository.save(updated);
        elasticsearchOperations.indexOps(ClubDocument.class).refresh();

        // then
        Optional<ClubDocument> found = clubElasticsearchRepository.findById(2L);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("강남 프리미엄 독서 클럽");
        assertThat(found.get().getMemberCount()).isEqualTo(20L);
    }
}
