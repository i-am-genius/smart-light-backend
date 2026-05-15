package com.genius.smartlight.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSocketSessionManager {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStoreMap = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUserMap = new ConcurrentHashMap<>();

    public void addSession(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket connected: sessionId={}", session.getId());
    }

    public void registerStore(String sessionId, Long storeId) {
        if (storeId != null) {
            sessionStoreMap.put(sessionId, storeId);
        }
    }

    public void registerUser(String sessionId, Long userId) {
        if (userId != null) {
            sessionUserMap.put(sessionId, userId);
        }
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session.getId());
        sessionStoreMap.remove(session.getId());
        sessionUserMap.remove(session.getId());
        log.info("WebSocket disconnected: sessionId={}", session.getId());
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (IOException e) {
            log.error("WebSocket send failed: sessionId={}", session.getId(), e);
        }
    }

    /**
     * 向指定店铺的所有浏览器客户端广播消息。
     * 仅推送给已关联该 storeId 的 session，实现多店铺数据隔离。
     */
    public void broadcastToStore(Long storeId, String payload) {
        if (storeId == null) {
            return;
        }
        sessions.forEach((sessionId, session) -> {
            Long sessionStoreId = sessionStoreMap.get(sessionId);
            if (storeId.equals(sessionStoreId)) {
                send(session, payload);
            }
        });
    }

    /**
     * 全局广播，仅用于非敏感的系统级消息（如 lightEffectState）。
     */
    public void broadcastAll(String payload) {
        sessions.values().forEach(session -> send(session, payload));
    }
}
