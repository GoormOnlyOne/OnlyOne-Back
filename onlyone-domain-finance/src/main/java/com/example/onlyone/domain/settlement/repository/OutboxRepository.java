package com.example.onlyone.domain.settlement.repository;

import com.example.onlyone.domain.settlement.dto.event.OutboxEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

}
