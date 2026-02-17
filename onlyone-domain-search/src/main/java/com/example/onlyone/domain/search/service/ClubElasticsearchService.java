package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.document.ClubDocument;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.retry.annotation.Retryable;
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

    // ES에 클럽 인덱싱 (비동기)
    @Async
    @Retryable
    public void indexClub(Club club) {
        try {
            ClubDocument document = ClubDocument.from(club);
            clubElasticsearchRepository.save(document);
            log.debug("Successfully indexed club: {}", club.getClubId());
        } catch (Exception e) {
            log.error("Failed to index club: {}", club.getClubId(), e);
            throw new CustomException(ErrorCode.ELASTICSEARCH_INDEX_ERROR);
        }
    }

    // ES에서 클럽 삭제 (비동기)
    @Async
    @Retryable
    public void deleteClub(Long clubId) {
        try {
            clubElasticsearchRepository.deleteById(clubId);
            log.debug("Successfully deleted club from ES: {}", clubId);
        } catch (Exception e) {
            log.error("Failed to delete club from ES: {}", clubId, e);
            throw new CustomException(ErrorCode.ELASTICSEARCH_DELETE_ERROR);
        }
    }

    // ES에서 클럽 업데이트 (비동기)
    @Async
    @Retryable
    public void updateClub(Club club) {
        try {
            ClubDocument document = ClubDocument.from(club);
            clubElasticsearchRepository.save(document);
            log.debug("Successfully updated club in ES: {}", club.getClubId());
        } catch (Exception e) {
            log.error("Failed to update club in ES: {}", club.getClubId(), e);
            throw new CustomException(ErrorCode.ELASTICSEARCH_UPDATE_ERROR);
        }
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