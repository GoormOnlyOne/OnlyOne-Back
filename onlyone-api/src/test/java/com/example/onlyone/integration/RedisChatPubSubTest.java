package com.example.onlyone.integration;

import com.example.onlyone.domain.chat.service.ChatPublisher;
import com.example.onlyone.support.AbstractRedisContainerTest;
import com.example.onlyone.support.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Chat Pub/Sub 통합 테스트.
 * 실제 Redis 컨테이너에서 ChatPublisher의 Pub/Sub 메시지 전달을 검증한다.
 */
@SpringBootTest
@Import(IntegrationTestConfig.class)
@DisplayName("Redis Chat Pub/Sub 통합 테스트")
class RedisChatPubSubTest extends AbstractRedisContainerTest {

    @Autowired
    private ChatPublisher chatPublisher;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    @DisplayName("publish시 구독자가 메시지를 수신한다")
    void publish_subscriberReceivesMessage() throws Exception {
        // given
        Long roomId = 42L;
        String expectedMessage = "{\"sender\":\"user1\",\"content\":\"hello\"}";
        String channel = "chat.room." + roomId;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // 구독자 설정
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        MessageListener listener = (message, pattern) -> {
            receivedMessage.set(new String(message.getBody()));
            latch.countDown();
        };
        container.addMessageListener(listener, new PatternTopic(channel));
        container.afterPropertiesSet();
        container.start();

        // 구독자가 준비될 시간을 주기
        Thread.sleep(500);

        // when
        chatPublisher.publish(roomId, expectedMessage);

        // then
        boolean received = latch.await(5, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedMessage.get()).isEqualTo(expectedMessage);

        // cleanup
        container.stop();
        container.destroy();
    }

    @Test
    @DisplayName("roomId가 null이면 발행하지 않는다")
    void publish_withNullRoomId_doesNotPublish() throws Exception {
        // given
        CountDownLatch latch = new CountDownLatch(1);

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        MessageListener listener = (message, pattern) -> latch.countDown();
        container.addMessageListener(listener, new PatternTopic("chat.room.*"));
        container.afterPropertiesSet();
        container.start();

        Thread.sleep(500);

        // when
        chatPublisher.publish(null, "test message");

        // then: 메시지가 오지 않아야 함 (1초 대기 후 타임아웃)
        boolean received = latch.await(1, TimeUnit.SECONDS);
        assertThat(received).isFalse();

        // cleanup
        container.stop();
        container.destroy();
    }
}
