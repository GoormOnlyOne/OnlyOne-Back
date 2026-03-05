package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.repository.FeedRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedPopularityScheduler {

    private final FeedRepository feedRepository;

    @Scheduled(fixedRate = 300_000) // 5분마다
    @Transactional
    public void updatePopularityScores() {
        int updated = feedRepository.updatePopularityScores();
        log.debug("인기도 스코어 갱신 완료: {}건", updated);
    }
}
