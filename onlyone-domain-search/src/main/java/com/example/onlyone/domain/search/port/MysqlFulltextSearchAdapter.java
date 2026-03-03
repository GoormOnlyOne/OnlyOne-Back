package com.example.onlyone.domain.search.port;

import com.example.onlyone.domain.interest.entity.Category;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.engine", havingValue = "mysql")
public class MysqlFulltextSearchAdapter implements SearchPort {

    private final EntityManager entityManager;

    @Override
    public List<ClubSearchResult> search(String keyword, String city, String district,
                                         Long interestId, Pageable pageable) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.club_id, c.name, c.description, ")
           .append("cat.category AS interest_korean_name, ")
           .append("c.district, c.member_count, c.club_image, ")
           .append("MATCH(c.name, c.description) AGAINST(:keyword IN BOOLEAN MODE) AS relevance ")
           .append("FROM club c ")
           .append("LEFT JOIN interest cat ON c.interest_id = cat.interest_id ")
           .append("WHERE MATCH(c.name, c.description) AGAINST(:keyword IN BOOLEAN MODE) ");

        if (city != null && !city.isBlank()) {
            sql.append("AND c.city = :city ");
        }
        if (district != null && !district.isBlank()) {
            sql.append("AND c.district = :district ");
        }
        if (interestId != null) {
            sql.append("AND c.interest_id = :interestId ");
        }

        sql.append("ORDER BY relevance DESC, c.member_count DESC ")
           .append("LIMIT :limit OFFSET :offset");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("keyword", toBooleanModeKeyword(keyword));

        if (city != null && !city.isBlank()) {
            query.setParameter("city", city);
        }
        if (district != null && !district.isBlank()) {
            query.setParameter("district", district);
        }
        if (interestId != null) {
            query.setParameter("interestId", interestId);
        }

        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", (int) pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<ClubSearchResult> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            results.add(new ClubSearchResult(
                    ((Number) row[0]).longValue(),           // clubId
                    (String) row[1],                         // name
                    (String) row[2],                         // description
                    mapCategoryToKorean((String) row[3]),    // interest
                    (String) row[4],                         // district
                    ((Number) row[5]).longValue(),            // memberCount
                    (String) row[6]                           // image
            ));
        }
        return results;
    }

    /**
     * BOOLEAN MODE 키워드 변환: 각 단어에 + 접두사를 붙여 AND 조건으로 검색.
     * 예: "축구 서울" → "+축구 +서울"
     */
    private String toBooleanModeKeyword(String keyword) {
        String[] tokens = keyword.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (!token.isBlank()) {
                sb.append("+").append(token).append("* ");
            }
        }
        return sb.toString().trim();
    }

    private String mapCategoryToKorean(String category) {
        if (category == null) return null;
        try {
            return Category.valueOf(category).getKoreanName();
        } catch (IllegalArgumentException e) {
            return category;
        }
    }
}
