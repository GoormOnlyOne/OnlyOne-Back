package com.example.onlyone.global.sse.event;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.sse.connection.SseConnectionManager;
import com.example.onlyone.global.sse.metrics.SseMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 이벤트 전송자 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@DisplayName("SSE 이벤트 전송자 테스트")
class SseEventSenderTest {

    @Autowired
    private SseEventSender eventSender;

    @Autowired
    private SseConnectionManager connectionManager;

    @Autowired
    private SseMetrics sseMetrics;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .userId(1L)
                .email("test@example.com")
                .name("테스트사용자")
                .status(Status.ACTIVE)
                .build();
        
        connectionManager.clearAllConnections();
    }

    @Test
    @DisplayName("연결된 사용자에게 이벤트 전송 성공")
    void sendEvent_ToConnectedUser_Success() throws Exception {
        // given
        connectionManager.createConnection(testUser);

        // when
        CompletableFuture<Boolean> result = eventSender.sendEvent(
                testUser.getUserId(), "test", "테스트 메시지"
        );

        // then
        assertThat(result.get()).isTrue();
    }

    @Test
    @DisplayName("연결되지 않은 사용자에게 이벤트 전송 실패")
    void sendEvent_ToUnconnectedUser_ReturnsFalse() throws Exception {
        // when
        CompletableFuture<Boolean> result = eventSender.sendEvent(
                999L, "test", "테스트 메시지"
        );

        // then
        assertThat(result.get()).isFalse();
    }

    @Test
    @DisplayName("동기 이벤트 전송 성공")
    void sendEventSync_Success() {
        // given
        connectionManager.createConnection(testUser);

        // when
        boolean result = eventSender.sendEventSync(
                testUser.getUserId(), "test", "테스트 메시지"
        );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("동기 이벤트 전송 - 연결 없음")
    void sendEventSync_NoConnection_ReturnsFalse() {
        // when
        boolean result = eventSender.sendEventSync(
                999L, "test", "테스트 메시지"
        );

        // then
        assertThat(result).isFalse();
    }
}