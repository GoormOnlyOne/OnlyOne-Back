package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.repository.FeedRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedPopularityBatchService {

    private final FeedRepository feedRepository;

    @Transactional
    public int updateBatch(int batchSize) {
        return feedRepository.updatePopularityScoresBatch(batchSize);
    }
}
