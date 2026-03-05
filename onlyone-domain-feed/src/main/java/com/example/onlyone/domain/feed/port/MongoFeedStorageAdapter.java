package com.example.onlyone.domain.feed.port;

import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.FeedDocument;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.feed.storage", havingValue = "mongodb")
public class MongoFeedStorageAdapter implements FeedStoragePort {

    private final MongoTemplate mongoTemplate;

    private static final String IDX_PERSONAL = "idx_personal_feed";

    // ── 개인 피드 (최신순) ──

    @Override
    public List<FeedIdWithCounts> findPersonalFeedIds(List<Long> clubIds, Pageable pageable) {
        Query query = new Query(Criteria.where("deleted").is(false)
                .and("clubId").in(clubIds))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize());
        query.fields().include("feedId", "likeCount", "commentCount");
        query.withHint(IDX_PERSONAL);

        return mongoTemplate.find(query, FeedDocument.class).stream()
                .map(d -> new FeedIdWithCounts(d.getFeedId(), d.getLikeCount(), d.getCommentCount()))
                .toList();
    }

    // ── 개인 피드 cursor 기반 ──

    @Override
    public List<FeedIdWithCounts> findPersonalFeedIdsCursor(List<Long> clubIds, Long cursor, int limit) {
        Criteria criteria = Criteria.where("deleted").is(false)
                .and("clubId").in(clubIds);
        if (cursor != null) {
            criteria = criteria.and("feedId").lt(cursor);
        }
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "feedId"))
                .limit(limit);
        query.fields().include("feedId", "likeCount", "commentCount");

        return mongoTemplate.find(query, FeedDocument.class).stream()
                .map(d -> new FeedIdWithCounts(d.getFeedId(), d.getLikeCount(), d.getCommentCount()))
                .toList();
    }

    // ── 인기 피드 (스코어순) ──

    @Override
    public List<FeedIdWithCounts> findPopularFeedIds(List<Long> clubIds, Pageable pageable) {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("clubId").in(clubIds)
                        .and("deleted").is(false)
                        .and("createdAt").gte(sevenDaysAgo)),
                Aggregation.addFields()
                        .addFieldWithValue("score",
                                new org.bson.Document("$subtract", List.of(
                                        new org.bson.Document("$ln",
                                                new org.bson.Document("$max", List.of(
                                                        new org.bson.Document("$add", List.of(
                                                                "$likeCount",
                                                                new org.bson.Document("$multiply", List.of("$commentCount", 2)),
                                                                new org.bson.Document("$cond", Arrays.asList(
                                                                        new org.bson.Document("$ne", Arrays.asList("$parentFeedId", null)),
                                                                        2, 0))
                                                        )),
                                                        1
                                                ))),
                                        new org.bson.Document("$divide", List.of(
                                                new org.bson.Document("$divide", List.of(
                                                        new org.bson.Document("$subtract", List.of("$$NOW", "$createdAt")),
                                                        1000
                                                )),
                                                43200.0
                                        ))
                                )))
                        .build(),
                Aggregation.sort(Sort.by(Sort.Direction.DESC, "score", "createdAt")),
                Aggregation.skip(pageable.getOffset()),
                Aggregation.limit(pageable.getPageSize()),
                Aggregation.project("feedId", "likeCount", "commentCount")
        );

        return mongoTemplate.aggregate(agg, "feed", FeedDocument.class).getMappedResults().stream()
                .map(d -> new FeedIdWithCounts(d.getFeedId(), d.getLikeCount(), d.getCommentCount()))
                .toList();
    }

    // ── 모임 피드 목록 ──

    @Override
    public Page<FeedSummaryResponseDto> findClubFeedSummaries(Long clubId, Pageable pageable) {
        Criteria criteria = Criteria.where("clubId").is(clubId)
                .and("deleted").is(false)
                .and("parentFeedId").is(null);

        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize());

        List<FeedDocument> docs = mongoTemplate.find(query, FeedDocument.class);
        long total = mongoTemplate.count(new Query(criteria), FeedDocument.class);

        List<FeedSummaryResponseDto> content = docs.stream()
                .map(d -> new FeedSummaryResponseDto(
                        d.getFeedId(),
                        d.getImageUrls() != null && !d.getImageUrls().isEmpty() ? d.getImageUrls().get(0) : null,
                        d.getLikeCount().intValue(),
                        d.getCommentCount().intValue()))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // ── 피드 상세 ──

    @Override
    public Optional<FeedDetailItem> findFeedDetailWithRelations(Long feedId, Long clubId) {
        Query query = new Query(Criteria.where("feedId").is(feedId)
                .and("clubId").is(clubId)
                .and("deleted").is(false));
        FeedDocument doc = mongoTemplate.findOne(query, FeedDocument.class);
        return Optional.ofNullable(doc).map(this::toDetailItem);
    }

    // ── 배치 피드 로딩 ──

    @Override
    public List<FeedDetailItem> findFeedsByIdsWithRelations(List<Long> feedIds) {
        if (feedIds.isEmpty()) return List.of();
        Query query = new Query(Criteria.where("feedId").in(feedIds).and("deleted").is(false));
        return mongoTemplate.find(query, FeedDocument.class).stream()
                .map(this::toDetailItem).toList();
    }

    // ── 리포스트 카운트 ──

    @Override
    public Map<Long, Long> countDirectRepostsInBatch(List<Long> feedIds) {
        if (feedIds.isEmpty()) return Map.of();

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("parentFeedId").in(feedIds).and("deleted").is(false)),
                Aggregation.group("parentFeedId").count().as("cnt"),
                Aggregation.project("cnt").and("_id").as("parentId")
        );

        return mongoTemplate.aggregate(agg, "feed", org.bson.Document.class).getMappedResults().stream()
                .collect(Collectors.toMap(
                        d -> ((Number) d.get("parentId")).longValue(),
                        d -> ((Number) d.get("cnt")).longValue()));
    }

    @Override
    public long countRepostsByParentId(Long feedId) {
        Query query = new Query(Criteria.where("parentFeedId").is(feedId).and("deleted").is(false));
        return mongoTemplate.count(query, FeedDocument.class);
    }

    // ── 댓글 (임베딩에서 페이지네이션) ──

    @Override
    public List<CommentItem> findCommentsByFeedId(Long feedId, Pageable pageable) {
        Query query = new Query(Criteria.where("feedId").is(feedId).and("deleted").is(false));
        query.fields().include("recentComments");
        FeedDocument doc = mongoTemplate.findOne(query, FeedDocument.class);

        if (doc == null || doc.getRecentComments() == null) return List.of();

        List<FeedDocument.EmbeddedComment> all = doc.getRecentComments();
        int from = (int) pageable.getOffset();
        int to = Math.min(from + pageable.getPageSize(), all.size());
        if (from >= all.size()) return List.of();

        return all.subList(from, to).stream()
                .map(c -> new CommentItem(c.getCommentId(), c.getUserId(), c.getNickname(),
                        c.getProfileImage(), c.getContent(), c.getCreatedAt()))
                .toList();
    }

    // ── 좋아요 체크 (임베딩 likerUserIds) ──

    @Override
    public boolean isLikedByUser(Long feedId, Long userId) {
        Query query = new Query(Criteria.where("feedId").is(feedId)
                .and("deleted").is(false)
                .and("likerUserIds").is(userId));
        return mongoTemplate.exists(query, FeedDocument.class);
    }

    @Override
    public Set<Long> findLikedFeedIdsByUser(List<Long> feedIds, Long userId) {
        if (feedIds.isEmpty()) return Set.of();
        Query query = new Query(Criteria.where("feedId").in(feedIds)
                .and("deleted").is(false)
                .and("likerUserIds").is(userId));
        query.fields().include("feedId");

        return mongoTemplate.find(query, FeedDocument.class).stream()
                .map(FeedDocument::getFeedId)
                .collect(Collectors.toSet());
    }

    // ── 매핑 ──

    private FeedDetailItem toDetailItem(FeedDocument d) {
        return new FeedDetailItem(
                d.getFeedId(), d.getContent(),
                d.getClubId(), d.getClubName(),
                d.getUserId(), d.getNickname(), d.getProfileImage(),
                d.getParentFeedId(), d.getRootFeedId(),
                d.getLikeCount(), d.getCommentCount(),
                d.getImageUrls() != null ? d.getImageUrls() : List.of(),
                d.getCreatedAt(), d.getModifiedAt()
        );
    }
}
