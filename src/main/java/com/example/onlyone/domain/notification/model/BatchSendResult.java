package com.example.onlyone.domain.notification.model;

import lombok.Getter;

/**
 * FCM 배치 전송 결과 DTO
 */
@Getter
public class BatchSendResult {
    private final long successCount;
    private final long failureCount;
    
    private BatchSendResult(long successCount, long failureCount) {
        this.successCount = successCount;
        this.failureCount = failureCount;
    }
    
    public static BatchSendResult of(long successCount, long failureCount) {
        return new BatchSendResult(successCount, failureCount);
    }
    
    public static BatchSendResult empty() {
        return new BatchSendResult(0, 0);
    }
    
    public long getTotalCount() {
        return successCount + failureCount;
    }
    
    public double getSuccessRate() {
        long total = getTotalCount();
        return total > 0 ? (double) successCount / total * 100 : 0;
    }
}