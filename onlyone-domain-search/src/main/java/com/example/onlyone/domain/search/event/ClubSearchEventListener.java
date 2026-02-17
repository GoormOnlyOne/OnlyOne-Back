package com.example.onlyone.domain.search.event;

import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.search.service.ClubElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Club 검색 이벤트 리스너
 * Club 생성 시 Elasticsearch에 자동 인덱싱
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class ClubSearchEventListener {

    private final ClubElasticsearchService clubElasticsearchService;
    private final ClubRepository clubRepository;

    /**
     * Club 생성 이벤트 처리 - ES 인덱싱
     * 트랜잭션 커밋 후 비동기로 실행
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleClubCreatedEvent(ClubCreatedEvent event) {
        log.info("[Event.Received] type=ClubCreatedEvent, target=Elasticsearch, clubId={}", event.clubId());

        try {
            Club club = clubRepository.findById(event.clubId())
                    .orElseThrow(() -> new IllegalArgumentException("Club not found: " + event.clubId()));

            clubElasticsearchService.indexClub(club);

            log.info("[Event.Completed] type=ClubCreatedEvent, target=Elasticsearch, clubId={}", event.clubId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ClubCreatedEvent, target=Elasticsearch, clubId={}", event.clubId(), e);
            // 인덱싱 실패는 비즈니스 로직에 영향을 주지 않으므로 예외를 던지지 않음
        }
    }
}
