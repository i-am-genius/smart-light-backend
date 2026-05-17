package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long storeId = readLongAttribute(session, AppWebSocketHandshakeInterceptor.ATTR_STORE_ID);
        Long userId = readLongAttribute(session, AppWebSocketHandshakeInterceptor.ATTR_USER_ID);
        String username = readStringAttribute(session, AppWebSocketHandshakeInterceptor.ATTR_USERNAME);
        if (storeId == null) {
            log.warn("[ws] event=connected, wsType=browser, sessionId={}, userId={}, username={}, storeId=missing, action=closed",
                    session.getId(), userId, username);
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("storeId required"));
            return;
        }

        sessionManager.addSession(session);
        sessionManager.registerStore(session.getId(), storeId);
        sessionManager.registerUser(session.getId(), userId);
        log.info("[ws] event=connected, wsType=browser, sessionId={}, userId={}, username={}, storeId={}",
                session.getId(), userId, username, storeId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("onlineCount", sessionManager.getSessionCount());

        sessionManager.send(session, objectMapper.writeValueAsString(WsMessage.of("connected", data)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();

            if ("ping".equals(type)) {
                sessionManager.send(session, objectMapper.writeValueAsString(WsMessage.of("pong", "ok")));
                return;
            }

            if ("auth".equals(type)) {
                sessionManager.send(session, objectMapper.writeValueAsString(WsMessage.of("auth", "already_authenticated")));
            }
        } catch (Exception e) {
            log.warn("Ignore invalid websocket message, sessionId={}, errorType={}",
                    session.getId(), e.getClass().getSimpleName());
            log.debug("Invalid app websocket payload preview: {}", preview(message.getPayload()), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = readLongAttribute(session, AppWebSocketHandshakeInterceptor.ATTR_USER_ID);
        Long storeId = readLongAttribute(session, AppWebSocketHandshakeInterceptor.ATTR_STORE_ID);
        boolean abnormal = status != null && !CloseStatus.NORMAL.equals(status);
        if (abnormal) {
            log.warn("[ws] event=disconnected, wsType=browser, sessionId={}, userId={}, storeId={}, closeStatus={}, closeReason={}",
                    session.getId(), userId, storeId,
                    status != null ? status.getCode() : "-",
                    status != null && status.getReason() != null ? status.getReason() : "");
        } else {
            log.info("[ws] event=disconnected, wsType=browser, sessionId={}, userId={}, storeId={}",
                    session.getId(), userId, storeId);
        }
        sessionManager.removeSession(session);
    }

    private Long readLongAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        return null;
    }

    private String readStringAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
