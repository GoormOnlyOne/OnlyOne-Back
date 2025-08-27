package com.example.onlyone.domain.notification.model;

import lombok.Getter;

/**
 * SSE 브로드캐스트 결과 DTO
 */
@Getter
public class BroadcastResult {
    private final long successCount;
    private final long failureCount;
    
    private BroadcastResult(long successCount, long failureCount) {
        this.successCount = successCount;
        this.failureCount = failureCount;
    }
    
    public static BroadcastResult of(long successCount, long failureCount) {
        return new BroadcastResult(successCount, failureCount);
    }
    
    public long getTotalCount() {
        return successCount + failureCount;
    }
    
    public double getSuccessRate() {
        long total = getTotalCount();
        return total > 0 ? (double) successCount / total * 100 : 0;
    }
}