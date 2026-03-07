package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.QFeed;
import com.example.onlyone.domain.feed.entity.QFeedImage;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FeedRepositoryCustomImpl implements FeedRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    private static final QFeed feed = QFeed.feed;
    private static final QFeedImage feedImage = QFeedImage.feedImage1;

    // ── 모임 피드 목록 ──

    @Override
    public Page<FeedSummaryResponseDto> findFeedSummaries(Long clubId, Pageable pageable) {
        QFeedImage firstImage = new QFeedImage("firstImage");

        List<FeedSummaryResponseDto> content = queryFactory
                .select(Projections.constructor(FeedSummaryResponseDto.class,
                        feed.feedId,
                        firstImage.feedImage,
                        feed.likeCount.intValue(),
                        feed.commentCount.intValue()))
                .from(feed)
                .leftJoin(firstImage)
                    .on(firstImage.feed.eq(feed)
                        .and(firstImage.feedImageId.eq(
                                JPAExpressions.select(feedImage.feedImageId.min())
                                        .from(feedImage)
                                        .where(feedImage.feed.eq(feed)))))
                .where(
                        feed.club.clubId.eq(clubId),
                        feed.parentFeedId.isNull())
                .orderBy(feed.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(feed.count())
                .from(feed)
                .where(
                        feed.club.clubId.eq(clubId),
                        feed.parentFeedId.isNull())
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    // ── 개인 피드 pass1 (최신순) ──

    @Override
    public List<FeedIdWithCounts> findFeedIdsByClubIds(List<Long> clubIds, Pageable pageable) {
        return queryFactory
                .select(Projections.constructor(FeedIdWithCounts.class,
                        feed.feedId,
                        feed.likeCount,
                        feed.commentCount))
                .from(feed)
                .where(feed.club.clubId.in(clubIds))
                .orderBy(feed.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    // ── 개인 피드 chunked — IN절 분할 후 병합 정렬 ──

    @Override
    public List<FeedIdWithCounts> findFeedIdsByClubIdsChunked(
            List<Long> clubIds, Pageable pageable, int chunkSize) {
        int limit = (int) pageable.getOffset() + pageable.getPageSize();

        List<FeedIdWithCounts> merged = new ArrayList<>();
        for (int i = 0; i < clubIds.size(); i += chunkSize) {
            List<Long> chunk = clubIds.subList(i, Math.min(i + chunkSize, clubIds.size()));

            List<FeedIdWithCounts> chunkResult = queryFactory
                    .select(Projections.constructor(FeedIdWithCounts.class,
                            feed.feedId,
                            feed.likeCount,
                            feed.commentCount))
                    .from(feed)
                    .where(feed.club.clubId.in(chunk))
                    .orderBy(feed.createdAt.desc())
                    .limit(limit)
                    .fetch();

            merged.addAll(chunkResult);
        }

        // 병합 후 재정렬 + 페이지네이션 (feedId DESC ≈ createdAt DESC)
        merged.sort(Comparator.comparing(FeedIdWithCounts::feedId).reversed());

        int fromIndex = (int) pageable.getOffset();
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), merged.size());
        if (fromIndex >= merged.size()) return List.of();
        return merged.subList(fromIndex, toIndex);
    }

    // ── 개인 피드 cursor 기반 (OFFSET 제거) ──

    @Override
    public List<FeedIdWithCounts> findFeedIdsByClubIdsCursor(
            List<Long> clubIds, Long cursor, int limit) {
        var query = queryFactory
                .select(Projections.constructor(FeedIdWithCounts.class,
                        feed.feedId,
                        feed.likeCount,
                        feed.commentCount))
                .from(feed)
                .where(
                        feed.club.clubId.in(clubIds),
                        cursor != null ? feed.feedId.lt(cursor) : null)
                .orderBy(feed.feedId.desc())
                .limit(limit);
        return query.fetch();
    }

    @Override
    public List<FeedIdWithCounts> findFeedIdsByClubIdsCursorChunked(
            List<Long> clubIds, Long cursor, int limit, int chunkSize) {
        List<FeedIdWithCounts> merged = new ArrayList<>();
        for (int i = 0; i < clubIds.size(); i += chunkSize) {
            List<Long> chunk = clubIds.subList(i, Math.min(i + chunkSize, clubIds.size()));
            merged.addAll(findFeedIdsByClubIdsCursor(chunk, cursor, limit));
        }
        // feedId DESC 정렬 후 limit 적용
        merged.sort(Comparator.comparing(FeedIdWithCounts::feedId).reversed());
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    // ── 인기 피드 pass1 (스코어 기반 — 런타임 계산, fallback) ──

    @Override
    public List<FeedIdWithCounts> findPopularFeedIdsByClubIds(
            List<Long> clubIds, Pageable pageable) {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        NumberExpression<Integer> refeedBonus = new CaseBuilder()
                .when(feed.parentFeedId.isNotNull()).then(2)
                .otherwise(0);

        NumberExpression<Long> rawScore = feed.likeCount
                .add(feed.commentCount.multiply(2))
                .add(refeedBonus);

        NumberExpression<Long> clampedScore = new CaseBuilder()
                .when(rawScore.gt(1L)).then(rawScore)
                .otherwise(1L);

        NumberExpression<Double> score = Expressions.numberTemplate(Double.class,
                "LN({0}) - (TIMESTAMPDIFF(SECOND, {1}, NOW()) / 43200.0)",
                clampedScore, feed.createdAt);

        return queryFactory
                .select(Projections.constructor(FeedIdWithCounts.class,
                        feed.feedId,
                        feed.likeCount,
                        feed.commentCount))
                .from(feed)
                .where(
                        feed.club.clubId.in(clubIds),
                        feed.createdAt.goe(sevenDaysAgo))
                .orderBy(score.desc(), feed.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    // ── 인기 피드 pass1 (pre-computed score — 인덱스 활용) ──

    @Override
    public List<FeedIdWithCounts> findPopularFeedIdsByScore(
            List<Long> clubIds, Pageable pageable) {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        return queryFactory
                .select(Projections.constructor(FeedIdWithCounts.class,
                        feed.feedId,
                        feed.likeCount,
                        feed.commentCount))
                .from(feed)
                .where(
                        feed.club.clubId.in(clubIds),
                        feed.createdAt.goe(sevenDaysAgo))
                .orderBy(feed.popularityScore.desc(), feed.feedId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    // ── 개인 피드 UNION ALL (IN절 제거 — 클럽별 개별 인덱스 활용) ──

    private static final String PERSONAL_SQL_TEMPLATE =
            "SELECT feed_id, like_count, comment_count FROM feed " +
            "WHERE club_id = ? AND deleted = false ORDER BY feed_id DESC LIMIT ?";

    private static final String PERSONAL_CURSOR_SQL_TEMPLATE =
            "SELECT feed_id, like_count, comment_count FROM feed " +
            "WHERE club_id = ? AND feed_id < ? AND deleted = false ORDER BY feed_id DESC LIMIT ?";

    @Override
    public List<FeedIdWithCounts> findFeedIdsByClubIdsUnionAll(
            List<Long> clubIds, Long cursor, int limit) {
        if (clubIds.isEmpty()) return List.of();

        // 클럽별 개별 쿼리 실행 후 Java에서 병합 (prepared statement 캐싱 활용)
        List<FeedIdWithCounts> merged = new ArrayList<>();
        String sql = cursor != null ? PERSONAL_CURSOR_SQL_TEMPLATE : PERSONAL_SQL_TEMPLATE;

        for (Number clubIdRaw : clubIds) {
            long clubId = clubIdRaw.longValue();
            Query query = entityManager.createNativeQuery(sql);
            if (cursor != null) {
                query.setParameter(1, clubId);
                query.setParameter(2, cursor);
                query.setParameter(3, limit);
            } else {
                query.setParameter(1, clubId);
                query.setParameter(2, limit);
            }

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            for (Object[] r : rows) {
                merged.add(new FeedIdWithCounts(
                        ((Number) r[0]).longValue(),
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue()));
            }
        }

        // feedId DESC 정렬 후 limit 적용
        merged.sort(Comparator.comparing(FeedIdWithCounts::feedId).reversed());
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    // ── 인기 피드 UNION ALL (IN절 제거 — 클럽별 score 인덱스 활용) ──

    @Override
    public List<FeedIdWithCounts> findPopularFeedIdsByScoreUnionAll(
            List<Long> clubIds, int limit) {
        if (clubIds.isEmpty()) return List.of();

        StringBuilder sql = new StringBuilder("SELECT feed_id, like_count, comment_count FROM (\n");
        for (int i = 0; i < clubIds.size(); i++) {
            if (i > 0) sql.append(" UNION ALL\n");
            sql.append("(SELECT feed_id, like_count, comment_count FROM feed WHERE club_id = :club")
               .append(i)
               .append(" AND deleted = false AND created_at >= NOW() - INTERVAL 7 DAY")
               .append(" ORDER BY popularity_score DESC LIMIT :lim)");
        }
        sql.append("\n) t ORDER BY feed_id DESC LIMIT :lim");

        Query query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < clubIds.size(); i++) {
            query.setParameter("club" + i, ((Number) clubIds.get(i)).longValue());
        }
        query.setParameter("lim", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(r -> new FeedIdWithCounts(
                        ((Number) r[0]).longValue(),
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue()))
                .toList();
    }

    // ── 리포스트 카운트 배치 ──

    @Override
    public List<ParentRepostCount> countDirectRepostsIn(List<Long> feedIds) {
        return queryFactory
                .select(Projections.constructor(ParentRepostCount.class,
                        feed.parentFeedId,
                        feed.count()))
                .from(feed)
                .where(feed.parentFeedId.in(feedIds))
                .groupBy(feed.parentFeedId)
                .fetch();
    }
}
