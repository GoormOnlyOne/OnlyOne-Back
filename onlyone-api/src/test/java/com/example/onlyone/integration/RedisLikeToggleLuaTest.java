package com.example.onlyone.integration;

import com.example.onlyone.support.AbstractRedisContainerTest;
import com.example.onlyone.support.IntegrationTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis like_toggle.lua 스크립트 통합 테스트.
 * 실제 Redis 컨테이너에서 Lua 스크립트의 원자적 동작을 검증한다.
 */
@SpringBootTest
@Import(IntegrationTestConfig.class)
@DisplayName("Redis 좋아요 Lua 스크립트 통합 테스트")
class RedisLikeToggleLuaTest extends AbstractRedisContainerTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DefaultRedisScript<List> likeToggleScript;

    private static final String FEED_ID = "100";
    private static final String USER_ID = "1";
    private static final String LIKERS_SET = "feed:likers:" + FEED_ID;
    private static final String COUNT_KEY = "feed:like_count:" + FEED_ID;
    private static final String STREAM_KEY = "like:events";

    @BeforeEach
    void setUp() {
        // 테스트 전에 관련 키 정리
        stringRedisTemplate.delete(LIKERS_SET);
        stringRedisTemplate.delete(COUNT_KEY);
        stringRedisTemplate.delete(STREAM_KEY);
        // idemp 키 패턴 삭제
        var idempKeys = stringRedisTemplate.keys("like:idemp:*");
        if (idempKeys != null && !idempKeys.isEmpty()) {
            stringRedisTemplate.delete(idempKeys);
        }
    }

    @Test
    @DisplayName("좋아요 토글시 SET에 userId가 추가되고 count가 증가한다")
    void toggleLike_addsUserToSetAndIncrementsCount() {
        // given
        String reqId = UUID.randomUUID().toString();
        String idempKey = "like:idemp:" + reqId;
        String nowMillis = String.valueOf(System.currentTimeMillis());

        // when
        List<?> result = stringRedisTemplate.execute(
                likeToggleScript,
                Arrays.asList(LIKERS_SET, COUNT_KEY, STREAM_KEY, idempKey),
                USER_ID, FEED_ID, reqId, nowMillis
        );

        // then
        assertThat(result).isNotNull();
        assertThat(((Number) result.get(0)).intValue()).isEqualTo(1);  // nowOn = 1 (좋아요 ON)
        assertThat(((Number) result.get(1)).intValue()).isEqualTo(1);  // delta = +1
        assertThat(((Number) result.get(2)).intValue()).isEqualTo(1);  // newCount = 1

        // SET에 userId가 존재하는지 확인
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(LIKERS_SET, USER_ID);
        assertThat(isMember).isTrue();

        // count 키 확인
        String count = stringRedisTemplate.opsForValue().get(COUNT_KEY);
        assertThat(count).isEqualTo("1");
    }

    @Test
    @DisplayName("좋아요 취소시 SET에서 userId가 제거되고 count가 감소한다")
    void toggleLikeOff_removesUserFromSetAndDecrementsCount() {
        // given: 먼저 좋아요를 ON 상태로 만든다
        String reqId1 = UUID.randomUUID().toString();
        String idempKey1 = "like:idemp:" + reqId1;
        String nowMillis = String.valueOf(System.currentTimeMillis());

        stringRedisTemplate.execute(
                likeToggleScript,
                Arrays.asList(LIKERS_SET, COUNT_KEY, STREAM_KEY, idempKey1),
                USER_ID, FEED_ID, reqId1, nowMillis
        );

        // when: 다시 토글 (좋아요 취소)
        String reqId2 = UUID.randomUUID().toString();
        String idempKey2 = "like:idemp:" + reqId2;

        List<?> result = stringRedisTemplate.execute(
                likeToggleScript,
                Arrays.asList(LIKERS_SET, COUNT_KEY, STREAM_KEY, idempKey2),
                USER_ID, FEED_ID, reqId2, nowMillis
        );

        // then
        assertThat(result).isNotNull();
        assertThat(((Number) result.get(0)).intValue()).isEqualTo(0);  // nowOn = 0 (좋아요 OFF)
        assertThat(((Number) result.get(1)).intValue()).isEqualTo(-1); // delta = -1
        assertThat(((Number) result.get(2)).intValue()).isEqualTo(0);  // newCount = 0

        // SET에서 userId가 제거되었는지 확인
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(LIKERS_SET, USER_ID);
        assertThat(isMember).isFalse();
    }

    @Test
    @DisplayName("동일 reqId로 중복요청시 멱등성이 보장된다")
    void duplicateRequest_isIdempotent() {
        // given
        String reqId = UUID.randomUUID().toString();
        String idempKey = "like:idemp:" + reqId;
        String nowMillis = String.valueOf(System.currentTimeMillis());

        // 첫 번째 요청
        List<?> firstResult = stringRedisTemplate.execute(
                likeToggleScript,
                Arrays.asList(LIKERS_SET, COUNT_KEY, STREAM_KEY, idempKey),
                USER_ID, FEED_ID, reqId, nowMillis
        );

        // when: 동일한 reqId로 재요청
        List<?> secondResult = stringRedisTemplate.execute(
                likeToggleScript,
                Arrays.asList(LIKERS_SET, COUNT_KEY, STREAM_KEY, idempKey),
                USER_ID, FEED_ID, reqId, nowMillis
        );

        // then: 두 번째 요청의 delta는 0 (멱등)
        assertThat(secondResult).isNotNull();
        assertThat(((Number) secondResult.get(1)).intValue()).isEqualTo(0); // delta = 0

        // count는 여전히 1
        String count = stringRedisTemplate.opsForValue().get(COUNT_KEY);
        assertThat(count).isEqualTo("1");
    }

    @Test
    @DisplayName("Stream에 이벤트가 발행된다")
    void likeToggle_publishesEventToStream() {
        // given
        String reqId = UUID.randomUUID().toString();
        String idempKey = "like:idemp:" + reqId;
        String nowMillis = String.valueOf(System.currentTimeMillis());

        // when
        stringRedisTemplate.execute(
                likeToggleScript,
                Arrays.asList(LIKERS_SET, COUNT_KEY, STREAM_KEY, idempKey),
                USER_ID, FEED_ID, reqId, nowMillis
        );

        // then: Stream에서 이벤트 읽기
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                .read(StreamReadOptions.empty().count(10),
                        StreamOffset.fromStart(STREAM_KEY));

        assertThat(records).isNotNull().isNotEmpty();

        MapRecord<String, Object, Object> record = records.getLast();
        assertThat(record.getValue().get("feedId")).isEqualTo(FEED_ID);
        assertThat(record.getValue().get("userId")).isEqualTo(USER_ID);
        assertThat(record.getValue().get("op")).isEqualTo("ON");
        assertThat(record.getValue().get("reqId")).isEqualTo(reqId);
    }
}
