package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface FeedRepositoryCustom {

    /** 모임 피드 목록 — 썸네일(첫 이미지) + 좋아요/댓글 수 */
    Page<FeedSummaryResponseDto> findFeedSummaries(Long clubId, Pageable pageable);

    /** 개인 피드 pass1 — ID + 비정규화 count (최신순) */
    List<FeedIdWithCounts> findFeedIdsByClubIds(List<Long> clubIds, Pageable pageable);

    /** 개인 피드 chunked — 대량 클럽 IN절 분할 조회 후 병합 */
    List<FeedIdWithCounts> findFeedIdsByClubIdsChunked(List<Long> clubIds, Pageable pageable, int chunkSize);

    /** 인기 피드 pass1 — 스코어 기반 정렬 */
    List<FeedIdWithCounts> findPopularFeedIdsByClubIds(List<Long> clubIds, Pageable pageable);

    /** 리포스트 카운트 배치 조회 */
    List<ParentRepostCount> countDirectRepostsIn(List<Long> feedIds);

    record FeedIdWithCounts(Long feedId, Long likeCount, Long commentCount) {}
    record ParentRepostCount(Long parentId, Long cnt) {}
}
