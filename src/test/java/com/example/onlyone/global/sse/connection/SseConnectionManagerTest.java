package com.example.onlyone.global.sse.connection;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.sse.metrics.SseMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSE 연결 관리자 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@DisplayName("SSE 연결 관리자 테스트")
class SseConnectionManagerTest {

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
        
        // 기존 연결 정리
        connectionManager.clearAllConnections();
    }

    @Test
    @DisplayName("SSE 연결 생성 성공")
    void createConnection_Success() {
        // when
        SseEmitter emitter = connectionManager.createConnection(testUser);

        // then
        assertThat(emitter).isNotNull();
        assertThat(connectionManager.isUserConnected(testUser.getUserId())).isTrue();
        assertThat(connectionManager.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("중복 연결 시 기존 연결 정리")
    void createConnection_WithDuplicateUser_ReplacesExistingConnection() {
        // given
        connectionManager.createConnection(testUser);
        assertThat(connectionManager.getActiveConnectionCount()).isEqualTo(1);

        // when - 같은 사용자로 다시 연결
        SseEmitter newEmitter = connectionManager.createConnection(testUser);

        // then
        assertThat(newEmitter).isNotNull();
        assertThat(connectionManager.getActiveConnectionCount()).isEqualTo(1); // 여전히 1개
    }

    @Test
    @DisplayName("연결 정리 성공")
    void cleanupConnection_Success() {
        // given
        connectionManager.createConnection(testUser);
        assertThat(connectionManager.isUserConnected(testUser.getUserId())).isTrue();

        // when
        connectionManager.cleanupConnection(testUser.getUserId());

        // then
        assertThat(connectionManager.isUserConnected(testUser.getUserId())).isFalse();
        assertThat(connectionManager.getActiveConnectionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("연결 지속 시간 조회")
    void getConnectionDuration_Success() {
        // given
        connectionManager.createConnection(testUser);

        // when
        String duration = connectionManager.getConnectionDuration(testUser.getUserId());

        // then
        assertThat(duration).isNotNull();
        assertThat(duration).contains("초");
    }

    @Test
    @DisplayName("모든 연결 정리")
    void clearAllConnections_Success() {
        // given
        User user2 = User.builder()
                .userId(2L)
                .email("test2@example.com")
                .name("테스트사용자2")
                .status(Status.ACTIVE)
                .build();
        
        connectionManager.createConnection(testUser);
        connectionManager.createConnection(user2);
        assertThat(connectionManager.getActiveConnectionCount()).isEqualTo(2);

        // when
        connectionManager.clearAllConnections();

        // then
        assertThat(connectionManager.getActiveConnectionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("활성 사용자 ID 목록 조회")
    void getActiveUserIds_Success() {
        // given
        User user2 = User.builder()
                .userId(2L)
                .email("test2@example.com")
                .name("테스트사용자2")
                .status(Status.ACTIVE)
                .build();
        
        connectionManager.createConnection(testUser);
        connectionManager.createConnection(user2);

        // when
        var activeUserIds = connectionManager.getActiveUserIds();

        // then
        assertThat(activeUserIds).hasSize(2);
        assertThat(activeUserIds).contains(testUser.getUserId(), user2.getUserId());
    }
}