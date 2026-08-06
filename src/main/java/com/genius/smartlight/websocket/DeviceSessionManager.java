package com.genius.smartlight.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DeviceSessionManager {

    private static final long ONLINE_TIMEOUT_MS = 15_000L;

    private final Map<String, WebSocketSession> deviceSessionMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionDeviceMap = new ConcurrentHashMap<>();
    private final Map<String, String> uploadTokenMap = new ConcurrentHashMap<>();

    public void registerDevice(String chipId, WebSocketSession session) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId == null) {
            log.warn("registerDevice ignored blank chipId, sessionId={}", session.getId());
            return;
        }
        logIfNormalized(chipId, normalizedChipId);

        String oldChipIdForSession = sessionDeviceMap.put(session.getId(), normalizedChipId);
        if (oldChipIdForSession != null && !oldChipIdForSession.equals(normalizedChipId)) {
            deviceSessionMap.remove(oldChipIdForSession, session);
        }

        WebSocketSession oldSession = deviceSessionMap.put(normalizedChipId, session);
        lastSeenMap.put(normalizedChipId, System.currentTimeMillis());

        if (oldSession != null && oldSession != session && oldSession.isOpen()) {
            try {
                oldSession.close();
            } catch (IOException e) {
                log.warn("[ws] event=close_old_session_failed, wsType=device, chipId={}, errorMsg={}", normalizedChipId, e.getMessage());
            }
        }

        // device_registered logged by DeviceWebSocketHandler
    }

    public String refreshUploadToken(String chipId) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId == null) {
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        uploadTokenMap.put(normalizedChipId, token);
        return token;
    }

    public boolean validateUploadToken(String chipId, String token) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null || token == null || token.isBlank()) {
            return false;
        }
        String expected = uploadTokenMap.get(trackedChipId);
        return expected != null && expected.equals(token.trim());
    }

    public void touch(String chipId) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId != null) {
            logIfNormalized(chipId, normalizedChipId);
            lastSeenMap.put(normalizedChipId, System.currentTimeMillis());
        }
    }

    public boolean isOnline(String chipId) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null) {
            return false;
        }

        WebSocketSession session = deviceSessionMap.get(trackedChipId);
        Long lastSeen = lastSeenMap.get(trackedChipId);

        return session != null
                && session.isOpen()
                && lastSeen != null
                && System.currentTimeMillis() - lastSeen <= ONLINE_TIMEOUT_MS;
    }

    public Long getLastSeen(String chipId) {
        String trackedChipId = resolveTrackedChipId(chipId);
        return trackedChipId == null ? null : lastSeenMap.get(trackedChipId);
    }

    public Set<String> getTrackedChipIds() {
        Set<String> chipIds = new HashSet<>(lastSeenMap.keySet());
        chipIds.addAll(deviceSessionMap.keySet());
        return chipIds;
    }

    public Set<String> getOnlineChipIds() {
        Set<String> result = new HashSet<>();
        for (String chipId : deviceSessionMap.keySet()) {
            if (isOnline(chipId)) {
                result.add(chipId);
            }
        }
        return result;
    }

    public boolean sendToDevice(String chipId, String payload) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null) {
            log.warn("sendToDevice failed: blank chipId={}", chipId);
            return false;
        }

        WebSocketSession session = deviceSessionMap.get(trackedChipId);
        if (session == null) {
            log.warn("sendToDevice failed: no session for chipId={}", trackedChipId);
            return false;
        }
        if (!session.isOpen()) {
            log.warn("sendToDevice failed: session closed for chipId={}", trackedChipId);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(payload));
            log.debug("sendToDevice success: chipId={}", trackedChipId);
            return true;
        } catch (IOException e) {
            log.error("[ws] event=send_failed, wsType=device, chipId={}, errorType={}, errorMsg={}",
                    trackedChipId, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    public String removeBySession(WebSocketSession session) {
        String targetChipId = sessionDeviceMap.remove(session.getId());
        if (targetChipId == null) {
            for (Map.Entry<String, WebSocketSession> entry : deviceSessionMap.entrySet()) {
                if (entry.getValue() == session) {
                    targetChipId = entry.getKey();
                    break;
                }
            }
        }

        if (targetChipId != null) {
            deviceSessionMap.remove(targetChipId, session);
            // disconnected logged by DeviceWebSocketHandler
        } else {
            log.info("[ws] event=disconnected, wsType=device, sessionId={}, chipId=unregistered", session.getId());
        }
        return targetChipId;
    }

    public String normalizeChipId(String chipId) {
        if (chipId == null) {
            return null;
        }
        String value = chipId.trim();
        return value.isEmpty() ? null : value;
    }

    private String resolveTrackedChipId(String chipId) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId == null) {
            return null;
        }
        logIfNormalized(chipId, normalizedChipId);
        if (deviceSessionMap.containsKey(normalizedChipId) || lastSeenMap.containsKey(normalizedChipId)) {
            return normalizedChipId;
        }
        String trackedChipId = findCaseInsensitiveKey(deviceSessionMap, normalizedChipId);
        if (trackedChipId == null) {
            trackedChipId = findCaseInsensitiveKey(lastSeenMap, normalizedChipId);
        }
        if (trackedChipId != null) {
            log.warn("chipId case mismatch, requested={}, tracked={}", normalizedChipId, trackedChipId);
            return trackedChipId;
        }
        return normalizedChipId;
    }

    private String findCaseInsensitiveKey(Map<String, ?> map, String chipId) {
        for (String trackedChipId : map.keySet()) {
            if (trackedChipId.equalsIgnoreCase(chipId)) {
                return trackedChipId;
            }
        }
        return null;
    }

    private void logIfNormalized(String rawChipId, String normalizedChipId) {
        if (rawChipId != null && !rawChipId.equals(normalizedChipId)) {
            log.warn("chipId normalized by trim, raw='{}', normalized='{}'", rawChipId, normalizedChipId);
        }
    }
}
