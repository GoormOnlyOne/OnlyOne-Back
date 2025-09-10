package com.example.onlyone.domain.notification.aspect;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 알림 서비스 Jaeger 트레이싱 Aspect
 * 모든 알림 관련 메서드 실행 시간 추적
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationTracingAspect {
    
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    
    /**
     * NotificationService 모든 메서드 트레이싱
     */
    @Around("@within(org.springframework.stereotype.Service) && within(com.example.onlyone.domain.notification.service..*)")
    public Object traceNotificationService(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String spanName = className + "." + methodName;
        
        // Jaeger Span 생성
        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("service", "notification")
                .tag("class", className)
                .tag("method", methodName)
                .start();
        
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            long startTime = System.currentTimeMillis();
            
            // 메서드 실행
            Object result = joinPoint.proceed();
            
            long duration = System.currentTimeMillis() - startTime;
            
            // 태그 추가
            span.tag("duration.ms", String.valueOf(duration));
            span.tag("success", "true");
            
            // 37초 문제 감지
            if (duration > 37000) {
                span.tag("issue", "37_second_problem");
                span.tag("alert", "critical");
                log.error("🚨 37초 문제 발생! Method: {}, Duration: {}ms", spanName, duration);
            } else if (duration > 5000) {
                span.tag("performance", "slow");
                log.warn("⚠️ 느린 메서드: {}, Duration: {}ms", spanName, duration);
            }
            
            return result;
            
        } catch (Exception e) {
            span.tag("success", "false");
            span.tag("error", e.getMessage());
            span.tag("error.type", e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Repository 메서드 트레이싱
     */
    @Around("@within(org.springframework.stereotype.Repository) && within(com.example.onlyone.domain.notification.repository..*)")
    public Object traceNotificationRepository(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String spanName = "DB." + methodName;
        
        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("db.type", "mysql")
                .tag("db.operation", methodName)
                .start();
        
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            long startTime = System.currentTimeMillis();
            
            Object result = joinPoint.proceed();
            
            long duration = System.currentTimeMillis() - startTime;
            span.tag("db.duration.ms", String.valueOf(duration));
            
            // Connection Leak 의심 (2-3초 사이)
            if (duration >= 2000 && duration <= 3000) {
                span.tag("issue", "possible_connection_leak");
                log.warn("⚠️ Connection Leak 의심: {}, Duration: {}ms", methodName, duration);
            }
            
            return result;
            
        } catch (Exception e) {
            span.tag("db.error", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * SSE 연결 트레이싱
     */
    @Around("execution(* com.example.onlyone.global.sse..*Controller.*(..))")
    public Object traceSSEConnection(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String spanName = "SSE." + methodName;
        
        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("transport", "sse")
                .tag("operation", methodName)
                .start();
        
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            long startTime = System.currentTimeMillis();
            
            Object result = joinPoint.proceed();
            
            long duration = System.currentTimeMillis() - startTime;
            span.tag("sse.duration.ms", String.valueOf(duration));
            
            if (methodName.equals("subscribe")) {
                span.tag("sse.connection", "established");
                
                // 연결 시간이 오래 걸리면
                if (duration > 5000) {
                    span.tag("sse.slow_connection", "true");
                    log.warn("⚠️ SSE 연결 지연: {}ms", duration);
                }
            }
            
            return result;
            
        } catch (Exception e) {
            span.tag("sse.error", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * 트랜잭션 트레이싱
     */
    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object traceTransaction(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String spanName = "TX." + methodName;
        
        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("tx.type", "transactional")
                .start();
        
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            long startTime = System.currentTimeMillis();
            
            Object result = joinPoint.proceed();
            
            long duration = System.currentTimeMillis() - startTime;
            span.tag("tx.duration.ms", String.valueOf(duration));
            
            // 긴 트랜잭션 경고
            if (duration > 10000) {
                span.tag("tx.long_running", "true");
                log.warn("⚠️ 긴 트랜잭션: {}, Duration: {}ms", methodName, duration);
            }
            
            return result;
            
        } catch (Exception e) {
            span.tag("tx.rollback", "true");
            span.tag("tx.error", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}