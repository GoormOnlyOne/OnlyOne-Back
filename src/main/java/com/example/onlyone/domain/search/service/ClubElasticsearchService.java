package com.example.onlyone.domain.search.service;

import com.example.onlyone.domain.club.document.ClubDocument;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.sql.SQLException;

@Service
@RequiredArgsConstructor
@Slf4j
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

    /**
     * MySQL의 모든 클럽 데이터를 Elasticsearch에 동기화 (메모리 최적화된 버전)
     */
    public void syncAllClubsToElasticsearch() {
        long totalClubs = 0;
        int totalProcessed = 0;
        int failedCount = 0;
        
        try {
            log.info("Starting NON-TRANSACTIONAL optimized full sync of clubs to Elasticsearch");
            
            // 기존 ES 데이터 모두 삭제
            try {
                clubElasticsearchRepository.deleteAll();
                log.info("Cleared existing Elasticsearch data");
            } catch (Exception e) {
                log.error("Failed to clear Elasticsearch data", e);
                throw new CustomException(ErrorCode.ELASTICSEARCH_SYNC_ERROR);
            }
            
            // 총 개수 확인 (DB 연결 문제 대응)
            try {
                totalClubs = clubRepository.count();
                log.info("Total clubs to sync: {}", totalClubs);
            } catch (Exception e) {
                log.error("Failed to get club count from database", e);
                if (e.getCause() instanceof SQLException) {
                    log.error("Database connection issue detected. Retrying...");
                    Thread.sleep(2000); // 2초 대기 후 재시도
                    totalClubs = clubRepository.count();
                } else {
                    throw new CustomException(ErrorCode.DATABASE_CONNECTION_ERROR);
                }
            }
            
            if (totalClubs == 0) {
                log.info("No clubs found to sync");
                return;
            }
            
            // 페이지 단위로 처리 (1000만건용 초소형 배치)
            int pageSize = 50; // 1000만건을 위한 매우 작은 배치
            int totalPages = (int) Math.ceil((double) totalClubs / pageSize);
            
            log.info("Processing {} clubs in {} pages (page size: {})", totalClubs, totalPages, pageSize);
            
            for (int page = 0; page < totalPages; page++) {
                int retryCount = 0;
                boolean pageSuccess = false;
                
                while (retryCount < 3 && !pageSuccess) {
                    try {
                        Pageable pageable = PageRequest.of(page, pageSize);
                        Page<Club> clubPage;
                        
                        // 데이터베이스 조회 재시도 로직
                        try {
                            clubPage = clubRepository.findAll(pageable);
                        } catch (Exception dbE) {
                            if (dbE.getCause() instanceof SQLException && retryCount < 2) {
                                log.warn("Database error on page {}, retry {} - waiting 1s", page, retryCount + 1);
                                Thread.sleep(1000);
                                retryCount++;
                                continue;
                            }
                            throw dbE;
                        }
                        
                        List<Club> clubs = clubPage.getContent();
                        
                        if (clubs.isEmpty()) {
                            pageSuccess = true;
                            break;
                        }
                        
                        // ClubDocument 리스트로 변환
                        List<ClubDocument> documents = new ArrayList<>();
                        for (Club club : clubs) {
                            try {
                                documents.add(ClubDocument.from(club));
                            } catch (Exception e) {
                                log.warn("Failed to convert club ID: {} to document", club.getClubId(), e);
                                failedCount++;
                            }
                        }
                        
                        // 벌크 저장 (배치 처리)
                        if (!documents.isEmpty()) {
                            try {
                                clubElasticsearchRepository.saveAll(documents);
                                totalProcessed += documents.size();
                                pageSuccess = true;
                                
                                log.info("Processed page {}/{} - {} clubs indexed (Total: {}/{})", 
                                    page + 1, totalPages, documents.size(), totalProcessed, totalClubs);
                                
                            } catch (Exception esE) {
                                if (retryCount < 2) {
                                    log.warn("Elasticsearch error on page {}, retry {} - waiting 2s", page, retryCount + 1);
                                    Thread.sleep(2000);
                                    retryCount++;
                                    continue;
                                }
                                throw esE;
                            }
                        } else {
                            pageSuccess = true;
                        }
                        
                        // GC를 위한 명시적 참조 해제
                        clubs.clear();
                        documents.clear();
                        
                        // 시스템 부하 완화를 위한 대기 (1000만건용)
                        Thread.sleep(500); // 매 배치마다 500ms 대기
                        
                        // 100페이지마다 더 긴 대기
                        if ((page + 1) % 100 == 0) {
                            log.info("Processed {} pages, taking 10 second break...", page + 1);
                            Thread.sleep(10000); // 10초 대기
                        }
                        
                    } catch (Exception e) {
                        if (retryCount < 2) {
                            log.error("Failed to process page {} (attempt {})", page, retryCount + 1, e);
                            retryCount++;
                            try {
                                Thread.sleep(2000 * retryCount); // 지수백오프
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        } else {
                            log.error("Failed to process page {} after 3 attempts", page, e);
                            failedCount += pageSize; // 페이지 전체 실패로 간주
                            pageSuccess = true; // 다음 페이지로 넘어감
                        }
                    }
                }
            }
            
            log.info("Full sync completed. Successfully indexed: {}, Failed: {}, Total: {}", 
                totalProcessed, failedCount, totalClubs);
            
        } catch (Exception e) {
            log.error("Critical error during sync - indexed: {}, failed: {}, total: {}", 
                totalProcessed, failedCount, totalClubs, e);
            throw new CustomException(ErrorCode.ELASTICSEARCH_SYNC_ERROR);
        }
    }

    /**
     * Elasticsearch와 MySQL 간 데이터 개수 비교
     */
    @Transactional(readOnly = true)
    public void compareDataCounts() {
        try {
            long mysqlCount = clubRepository.count();
            long esCount = clubElasticsearchRepository.count();
            
            log.info("Data count comparison - MySQL: {}, Elasticsearch: {}", mysqlCount, esCount);
            
            if (mysqlCount != esCount) {
                log.warn("Data count mismatch detected! MySQL: {}, Elasticsearch: {}", mysqlCount, esCount);
            } else {
                log.info("Data counts match successfully");
            }
        } catch (Exception e) {
            log.error("Failed to compare data counts", e);
        }
    }

}