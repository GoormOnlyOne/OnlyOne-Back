package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeedRepository extends JpaRepository<Feed,Long> {
    Optional<Feed> findByFeedIdAndClub(Long feedId, Club club);

    Page<Feed> findByClubAndParentIsNull(Club club, Pageable pageable);

    Feed findByFeedId(Long feedId);

    @Query("SELECT f FROM Feed f WHERE f.club.clubId IN :clubIds")
    List<Feed> findByClubIds(List<Long> clubIds, Pageable pageable);

    @Query(
            value = """
        SELECT f.* 
          FROM feed f
         WHERE f.club_id IN (:clubIds)
         ORDER BY
           (
             LOG(GREATEST(
               (SELECT COUNT(*) FROM feed_like    fl WHERE fl.feed_id    = f.feed_id)
             + (SELECT COUNT(*) FROM feed_comment fc WHERE fc.feed_id    = f.feed_id) * 2
             , 1))
             - (TIMESTAMPDIFF(HOUR, f.created_at, NOW()) / 12.0)
           ) DESC,
           f.created_at DESC
        LIMIT :#{#pageable.offset}, :#{#pageable.pageSize}
      """,
            nativeQuery = true
    )
    List<Feed> findPopularByClubIds(@Param("clubIds") List<Long> clubIds, Pageable pageable);


}