package com.genius.smartlight.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSocketSessionManager {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStoreMap = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUserMap = new ConcurrentHashMap<>();

    public void addSession(WebSocketSession session) {
        WebSocketSession managedSession = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MS,
                BUFFER_SIZE_LIMIT_BYTES
        );
        sessions.put(session.getId(), managedSession);
        // connection logged by AppWebSocketHandler
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
        removeSessionById(session.getId());
        // disconnection logged by AppWebSocketHandler
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        WebSocketSession targetSession = sessions.getOrDefault(session.getId(), session);
        if (!targetSession.isOpen()) {
            removeSessionById(session.getId());
            return;
        }
        try {
            targetSession.sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            removeSessionById(session.getId());
            closeQuietly(targetSession);
            log.warn("[ws] event=send_failed, sessionId={}, errorType={}, errorMsg={}, action=removed",
                    session.getId(), e.getClass().getSimpleName(), e.getMessage());
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

    private void removeSessionById(String sessionId) {
        sessions.remove(sessionId);
        sessionStoreMap.remove(sessionId);
        sessionUserMap.remove(sessionId);
    }

    private void closeQuietly(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close();
        } catch (IOException e) {
            log.debug("[ws] event=close_failed, sessionId={}, errorType={}, errorMsg={}",
                    session.getId(), e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
