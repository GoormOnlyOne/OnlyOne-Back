package com.example.onlyone.domain.chat;

import com.example.onlyone.config.TestSecurityConfig;
import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.service.MessageService;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class ChatWebSocketTest {

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private MessageService messageService;

    @MockitoBean
    private com.google.firebase.messaging.FirebaseMessaging firebaseMessaging;

    @MockitoBean
    private UserService userService;

    @BeforeEach
    void setup() {
        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void sendMessage_thenAllSubscribersReceive() throws Exception {
        // given
        ChatMessageResponse mockResponse = ChatMessageResponse.builder()
                .messageId(1L)
                .chatRoomId(101L)
                .senderId(1001L)
                .text("안녕")
                .deleted(false)
                .sentAt(LocalDateTime.now())
                .build();

        when(messageService.saveMessage(eq(101L), eq(1001L), eq("안녕")))
                .thenReturn(mockResponse);

        String url = "http://localhost:" + port + "/ws";
        StompSession session = stompClient
                .connect(url, new StompSessionHandlerAdapter() {})
                .get(5, SECONDS);

        BlockingQueue<ChatMessageResponse> queue = new LinkedBlockingQueue<>();
        CountDownLatch messageLatch = new CountDownLatch(1);

        // 구독
        session.subscribe("/sub/chat/101/messages", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class; // 👈 payload를 byte[]로 받음
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    String json = new String((byte[]) payload, StandardCharsets.UTF_8);
                    System.out.println("📩 raw json = " + json);

                    ObjectMapper mapper = new ObjectMapper()
                            .registerModule(new JavaTimeModule())
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

                    ChatMessageResponse res = mapper.readValue(json, ChatMessageResponse.class);
                    System.out.println("✅ converted DTO = " + res);

                    queue.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    messageLatch.countDown();
                }
            }
        });

        // when
        ChatMessageRequest req = ChatMessageRequest.fromText(1001L, "안녕");
        session.send("/pub/chat/101/messages", req);

        // then
        assertTrue(messageLatch.await(5, SECONDS), "메시지를 제때 받지 못했습니다");
        ChatMessageResponse received = queue.poll();
        assertThat(received).isNotNull();
        assertThat(received.getText()).isEqualTo("안녕");
        assertThat(received.getSenderId()).isEqualTo(1001L);

        verify(messageService).saveMessage(101L, 1001L, "안녕");
    }

    @Test
    void expiredToken_thenReauthAndResubscribe_successfullyReceiveMessages() throws Exception {
        Long userId = 1001L;
        Long chatRoomId = 101L;

        User dummyUser = User.builder()
                .userId(userId)
                .kakaoId(99999L)
                .nickname("test-user")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.now())
                .build();

        String expiredToken = "expired.token";
        String newToken = "valid.jwt.token"; // 👈 진짜 JWT 검증할 필요 없으므로, 그냥 문자열로 대체
        when(userService.generateAccessToken(any(User.class))).thenReturn(newToken);

        ChatMessageResponse mockResponse = ChatMessageResponse.builder()
                .messageId(1L)
                .chatRoomId(chatRoomId)
                .senderId(userId)
                .text("재연결 후 메시지")
                .deleted(false)
                .sentAt(LocalDateTime.now())
                .build();

        when(messageService.saveMessage(eq(chatRoomId), eq(userId), eq("재연결 후 메시지")))
                .thenReturn(mockResponse);

        String url = "ws://localhost:" + port + "/ws";

        // STEP 1: 만료 토큰으로 연결 → disconnect
        StompHeaders expiredHeaders = new StompHeaders();
        expiredHeaders.add("Authorization", "Bearer " + expiredToken);

        StompSession expiredSession = stompClient
                .connect(url, new WebSocketHttpHeaders(), expiredHeaders, new StompSessionHandlerAdapter() {})
                .get(5, SECONDS);
        expiredSession.disconnect();

        // STEP 2: 새 토큰으로 재연결
        BlockingQueue<ChatMessageResponse> queue = new LinkedBlockingQueue<>();
        CountDownLatch latch = new CountDownLatch(1);

        StompHeaders newHeaders = new StompHeaders();
        newHeaders.add("Authorization", "Bearer " + newToken);

        StompSession newSession = stompClient
                .connect(url, new WebSocketHttpHeaders(), newHeaders, new StompSessionHandlerAdapter() {})
                .get(5, SECONDS);

        newSession.subscribe("/sub/chat/" + chatRoomId + "/messages", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    String json = new String((byte[]) payload, StandardCharsets.UTF_8);
                    ObjectMapper mapper = new ObjectMapper()
                            .registerModule(new JavaTimeModule())
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

                    ChatMessageResponse res = mapper.readValue(json, ChatMessageResponse.class);
                    queue.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }
        });

        ChatMessageRequest req = ChatMessageRequest.fromText(userId, "재연결 후 메시지");
        newSession.send("/pub/chat/" + chatRoomId + "/messages", req);

        assertTrue(latch.await(5, SECONDS), "메시지를 받지 못했습니다");
        ChatMessageResponse received = queue.poll();

        assertThat(received).isNotNull();
        assertThat(received.getText()).isEqualTo("재연결 후 메시지");
        assertThat(received.getSenderId()).isEqualTo(userId);

        verify(messageService).saveMessage(chatRoomId, userId, "재연결 후 메시지");
    }
}