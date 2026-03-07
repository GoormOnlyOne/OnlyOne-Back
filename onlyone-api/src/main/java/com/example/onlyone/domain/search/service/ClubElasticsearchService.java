package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.document.ClubDocument;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.example.onlyone.domain.club.repository.ClubRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class ClubElasticsearchService {

    private final ClubElasticsearchRepository clubElasticsearchRepository;
    private final ClubRepository clubRepository;

    // ES에 클럽 upsert (비동기 + 재시도) — save()는 동일 ID 존재 시 덮어쓰기
    @Async
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void upsertClub(Club club) {
        ClubDocument document = ClubDocument.from(club);
        clubElasticsearchRepository.save(document);
        log.info("ES 클럽 인덱싱 완료: clubId={}", club.getClubId());
    }

    @Recover
    public void recoverUpsertClub(Exception e, Club club) {
        log.error("ES 클럽 인덱싱 최종 실패 (재시도 소진): clubId={}", club.getClubId(), e);
    }

    // ES에서 클럽 삭제 (비동기 + 재시도)
    @Async
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void deleteClub(Long clubId) {
        clubElasticsearchRepository.deleteById(clubId);
        log.info("ES 클럽 삭제 완료: clubId={}", clubId);
    }

    @Recover
    public void recoverDeleteClub(Exception e, Long clubId) {
        log.error("ES 클럽 삭제 최종 실패 (재시도 소진): clubId={}", clubId, e);
    }

    // DB에서 전체 클럽을 페이지 단위로 읽어 ES에 벌크 인덱싱
    @Async
    @Transactional(readOnly = true)
    public void reindexAll() {
        log.info("Starting full club reindexing to Elasticsearch");
        int batchSize = 100;
        int page = 0;
        long totalIndexed = 0;

        Page<Club> clubPage;
        do {
            clubPage = clubRepository.findAll(PageRequest.of(page, batchSize));
            List<ClubDocument> documents = clubPage.getContent().stream()
                    .map(ClubDocument::from)
                    .toList();

            if (!documents.isEmpty()) {
                clubElasticsearchRepository.saveAll(documents);
                totalIndexed += documents.size();
                log.info("Reindex progress: indexed {} / {} clubs (page {})",
                        totalIndexed, clubPage.getTotalElements(), page);
            }
            page++;
        } while (clubPage.hasNext());

        log.info("Full club reindexing completed: {} clubs indexed", totalIndexed);
    }

}