package com.example.onlyone.global.reactive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.chat.websocket", havingValue = "reactive")
public class ReactiveChatConnectionManager {

    private final Map<Long, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> sessionRooms = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    public void registerSession(WebSocketSession session, Long userId) {
        sessionUsers.put(session.getId(), userId);
        sessionRooms.put(session.getId(), ConcurrentHashMap.newKeySet());
        log.info("Reactive WS 세션 등록: sessionId={}, userId={}", session.getId(), userId);
    }

    public void subscribeRoom(WebSocketSession session, Long roomId) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        Set<Long> rooms = sessionRooms.get(session.getId());
        if (rooms != null) {
            rooms.add(roomId);
        }
        log.debug("방 구독: sessionId={}, roomId={}, roomSize={}",
                session.getId(), roomId, getRoomCount(roomId));
    }

    public void unsubscribeRoom(WebSocketSession session, Long roomId) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
        Set<Long> rooms = sessionRooms.get(session.getId());
        if (rooms != null) {
            rooms.remove(roomId);
        }
        log.debug("방 구독 해제: sessionId={}, roomId={}", session.getId(), roomId);
    }

    public void broadcast(Long roomId, String jsonMessage) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        TextMessage textMessage = new TextMessage(jsonMessage);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(textMessage);
            } catch (IOException e) {
                log.warn("메시지 전송 실패, 세션 제거: sessionId={}, roomId={}", session.getId(), roomId, e);
                sessions.remove(session);
            }
        }
    }

    public void removeSession(WebSocketSession session) {
        Set<Long> rooms = sessionRooms.remove(session.getId());
        if (rooms != null) {
            for (Long roomId : rooms) {
                Set<WebSocketSession> sessions = roomSessions.get(roomId);
                if (sessions != null) {
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        roomSessions.remove(roomId);
                    }
                }
            }
        }
        Long userId = sessionUsers.remove(session.getId());
        log.info("Reactive WS 세션 제거: sessionId={}, userId={}", session.getId(), userId);
    }

    public int getConnectionCount() {
        return sessionUsers.size();
    }

    public int getRoomCount(Long roomId) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        return sessions == null ? 0 : sessions.size();
    }

    @Scheduled(fixedRate = 120_000)
    public void cleanupZombieSessions() {
        int cleaned = 0;
        Iterator<Map.Entry<String, Long>> it = sessionUsers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            String sessionId = entry.getKey();

            Set<Long> rooms = sessionRooms.get(sessionId);
            if (rooms == null) {
                it.remove();
                cleaned++;
                continue;
            }

            // Check if any session in any room matches this sessionId and is closed
            boolean isZombie = true;
            for (Long roomId : rooms) {
                Set<WebSocketSession> sessions = roomSessions.get(roomId);
                if (sessions != null) {
                    for (WebSocketSession s : sessions) {
                        if (s.getId().equals(sessionId) && s.isOpen()) {
                            isZombie = false;
                            break;
                        }
                    }
                }
                if (!isZombie) break;
            }

            if (isZombie) {
                // Remove from all rooms
                for (Long roomId : rooms) {
                    Set<WebSocketSession> sessions = roomSessions.get(roomId);
                    if (sessions != null) {
                        sessions.removeIf(s -> s.getId().equals(sessionId));
                        if (sessions.isEmpty()) {
                            roomSessions.remove(roomId);
                        }
                    }
                }
                sessionRooms.remove(sessionId);
                it.remove();
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("좀비 세션 정리: {}건, 현재 연결: {}건", cleaned, getConnectionCount());
        }
    }
}
