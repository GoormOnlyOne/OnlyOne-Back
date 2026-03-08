package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.feed.service.FeedCommentService.CommentCountEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * comment_count 비동기 갱신.
 * - TX 커밋 후 전용 스레드풀(max 10)에서 개별 UPDATE 실행
 * - feed row X-lock을 요청 critical path에서 제거 (260ms 달성)
 * - 스레드 풀 제한으로 커넥션 소비 최대 10개 (풀 400개의 2.5%)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCountEventListener {

    private final FeedRepository feedRepository;

    @Async("commentCountExecutor")
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleCommentCountEvent(CommentCountEvent event) {
        try {
            if (event.delta() > 0) {
                feedRepository.incrementCommentCount(event.feedId());
            } else {
                feedRepository.decrementCommentCount(event.feedId());
            }
        } catch (Exception e) {
            log.warn("comment_count 비동기 갱신 실패: feedId={}, delta={}", event.feedId(), event.delta(), e);
        }
    }
}
