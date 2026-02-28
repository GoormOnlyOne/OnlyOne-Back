package com.example.onlyone.sse.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Redis 기반 분산 SSE 연결 레지스트리.
 * 각 인스턴스가 자신에게 연결된 유저 목록을 Redis SET으로 관리한다.
 *
 * <p>키 구조:
 * <ul>
 *   <li>{@code sse:instance:{instanceId}} — 해당 인스턴스에 연결된 유저 ID SET</li>
 *   <li>{@code sse:user:{userId}} — 해당 유저가 연결된 인스턴스 ID</li>
 * </ul>
 *
 * {@code app.notification.multi-instance=true} 일 때 활성화된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.multi-instance", havingValue = "true")
public class DistributedConnectionRegistry {

    private static final String INSTANCE_KEY_PREFIX = "sse:instance:";
    private static final String USER_KEY_PREFIX = "sse:user:";
    private static final Duration KEY_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final String instanceId;

    public DistributedConnectionRegistry(
            StringRedisTemplate redis,
            @Value("${app.notification.instance-id:#{T(java.util.UUID).randomUUID().toString().substring(0,8)}}") String instanceId) {
        this.redis = redis;
        this.instanceId = instanceId;
        log.info("분산 연결 레지스트리 초기화: instanceId={}", instanceId);
    }

    /**
     * 유저 연결 등록.
     * SseConnectionManager.createConnection() 호출 시 함께 호출된다.
     */
    public void register(Long userId) {
        try {
            String instanceKey = INSTANCE_KEY_PREFIX + instanceId;
            String userKey = USER_KEY_PREFIX + userId;

            redis.opsForSet().add(instanceKey, String.valueOf(userId));
            redis.expire(instanceKey, KEY_TTL);

            redis.opsForValue().set(userKey, instanceId, KEY_TTL);

            log.debug("분산 레지스트리 등록: userId={}, instanceId={}", userId, instanceId);
        } catch (Exception e) {
            log.warn("분산 레지스트리 등록 실패: userId={}", userId, e);
        }
    }

    /**
     * 유저 연결 해제.
     * SseConnectionManager.cleanupConnection() 호출 시 함께 호출된다.
     */
    public void unregister(Long userId) {
        try {
            String instanceKey = INSTANCE_KEY_PREFIX + instanceId;
            String userKey = USER_KEY_PREFIX + userId;

            redis.opsForSet().remove(instanceKey, String.valueOf(userId));
            redis.delete(userKey);

            log.debug("분산 레지스트리 해제: userId={}, instanceId={}", userId, instanceId);
        } catch (Exception e) {
            log.warn("분산 레지스트리 해제 실패: userId={}", userId, e);
        }
    }

    /**
     * 유저가 어떤 인스턴스에 연결되어 있는지 조회.
     * MQ consumer가 호출하여, 해당 유저의 연결이 자신의 인스턴스에 있는지 확인한다.
     */
    public boolean isUserOnThisInstance(Long userId) {
        try {
            String userKey = USER_KEY_PREFIX + userId;
            String connectedInstance = redis.opsForValue().get(userKey);
            return instanceId.equals(connectedInstance);
        } catch (Exception e) {
            log.warn("분산 레지스트리 조회 실패: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 유저가 어느 인스턴스든 연결되어 있는지 확인.
     */
    public boolean isUserConnectedAnywhere(Long userId) {
        try {
            String userKey = USER_KEY_PREFIX + userId;
            return Boolean.TRUE.equals(redis.hasKey(userKey));
        } catch (Exception e) {
            log.warn("분산 레지스트리 존재 확인 실패: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 현재 인스턴스에 연결된 유저 목록 조회.
     */
    public Set<String> getConnectedUsers() {
        try {
            String instanceKey = INSTANCE_KEY_PREFIX + instanceId;
            return redis.opsForSet().members(instanceKey);
        } catch (Exception e) {
            log.warn("분산 레지스트리 유저 목록 조회 실패", e);
            return Set.of();
        }
    }

    /**
     * TTL 갱신 — 주기적으로 호출하여 키 만료 방지.
     */
    public void refreshTtl() {
        try {
            String instanceKey = INSTANCE_KEY_PREFIX + instanceId;
            redis.expire(instanceKey, KEY_TTL);

            Set<String> users = redis.opsForSet().members(instanceKey);
            if (users != null) {
                for (String userId : users) {
                    redis.expire(USER_KEY_PREFIX + userId, KEY_TTL);
                }
            }
        } catch (Exception e) {
            log.warn("분산 레지스트리 TTL 갱신 실패", e);
        }
    }

    public String getInstanceId() {
        return instanceId;
    }
}
