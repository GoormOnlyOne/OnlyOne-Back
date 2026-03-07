package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.event.OutboxEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
      SELECT * FROM outbox_event
      WHERE status = 'NEW'
      ORDER BY id ASC
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
    List<OutboxEvent> pickNewForUpdateSkipLocked(@Param("limit") int limit);

    @Query(value = """
      SELECT * FROM outbox_event
      WHERE status = 'FAILED'
        AND retry_count < :maxRetries
      ORDER BY id ASC
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
    List<OutboxEvent> findFailedForRetry(@Param("maxRetries") int maxRetries, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = com.example.onlyone.domain.settlement.entity.OutboxStatus.PUBLISHED AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = com.example.onlyone.domain.settlement.entity.OutboxStatus.DEAD AND e.createdAt < :cutoff")
    int deleteDeadBefore(@Param("cutoff") LocalDateTime cutoff);
}
